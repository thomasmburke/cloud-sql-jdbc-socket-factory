/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.sql.core;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles periodic refresh operations for an instance. */
class Refresher {
  private static final Logger logger = Logger.getLogger(Refresher.class.getName());

  private final ListeningScheduledExecutorService executor;

  private final Object instanceDataGuard = new Object();
  private final AsyncRateLimiter rateLimiter;

  private final RefreshCalculator refreshCalculator = new RefreshCalculator();
  private final Supplier<ListenableFuture<InstanceData>> refreshOperation;
  private final String name;

  @GuardedBy("instanceDataGuard")
  private ListenableFuture<InstanceData> currentInstanceData;

  @GuardedBy("instanceDataGuard")
  private ListenableFuture<InstanceData> nextInstanceData;

  @GuardedBy("instanceDataGuard")
  private boolean refreshRunning;

  @GuardedBy("instanceDataGuard")
  private Throwable currentRefreshFailure;

  /**
   * Create a new refresher.
   *
   * @param name the name of what is being refreshed, for logging.
   * @param executor the executor to schedule refresh tasks.
   * @param refreshOperation The supplier that refreshes the data.
   * @param rateLimiter The rate limiter.
   */
  Refresher(
      String name,
      ListeningScheduledExecutorService executor,
      Supplier<ListenableFuture<InstanceData>> refreshOperation,
      AsyncRateLimiter rateLimiter) {
    this.name = name;
    this.executor = executor;
    this.refreshOperation = refreshOperation;
    this.rateLimiter = rateLimiter;
    synchronized (instanceDataGuard) {
      forceRefresh();
      this.currentInstanceData = this.nextInstanceData;
    }
  }

  /**
   * Returns the current data related to the instance from {@link #startRefreshAttempt()}. May block
   * if no valid data is currently available. This method is called by an application thread when it
   * is trying to create a new connection to the database. (It is not called by a
   * ListeningScheduledExecutorService task.) So it is OK to block waiting for a future to complete.
   *
   * <p>When no refresh attempt is in progress, this returns immediately. Otherwise, it waits up to
   * timeoutMs milliseconds. If a refresh attempt succeeds, returns immediately at the end of that
   * successful attempt. If no attempts succeed within the timeout, throws a RuntimeException with
   * the exception from the last failed refresh attempt as the cause.
   */
  InstanceData getData(long timeoutMs) {
    ListenableFuture<InstanceData> instanceDataFuture;
    synchronized (instanceDataGuard) {
      instanceDataFuture = currentInstanceData;
    }

    try {
      return instanceDataFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      synchronized (instanceDataGuard) {
        if (currentRefreshFailure != null) {
          throw new RuntimeException(
              String.format(
                      "Unable to get valid instance data within %d ms."
                          + " Last refresh attempt failed:",
                      timeoutMs)
                  + currentRefreshFailure.getMessage(),
              currentRefreshFailure);
        }
      }
      throw new RuntimeException(
          String.format(
              "Unable to get valid instance data within %d ms. No refresh has completed.",
              timeoutMs),
          e);
    } catch (ExecutionException | InterruptedException ex) {
      Throwable cause = ex.getCause();
      Throwables.throwIfUnchecked(cause);
      throw new RuntimeException(cause);
    }
  }

  /**
   * Attempts to force a new refresh of the instance data. May fail if called too frequently or if a
   * new refresh is already in progress. If successful, other methods will block until refresh has
   * been completed.
   */
  void forceRefresh() {
    synchronized (instanceDataGuard) {
      // Don't force a refresh until the current refresh operation
      // has produced a successful refresh.
      if (refreshRunning) {
        return;
      }

      if (nextInstanceData != null) {
        nextInstanceData.cancel(false);
      }

      logger.fine(
          String.format(
              "[%s] Force Refresh: the next refresh operation was cancelled."
                  + " Scheduling new refresh operation immediately.",
              name));
      nextInstanceData = this.startRefreshAttempt();
    }
  }

  /**
   * Triggers an update of internal information obtained from the Cloud SQL Admin API, returning a
   * future that resolves once a valid T has been acquired. This sets up a chain of futures that
   * will 1. Acquire a rate limiter. 2. Attempt to fetch instance data. 3. Schedule the next attempt
   * to get instance data based on the success/failure of this attempt.
   *
   * @see com.google.cloud.sql.core.Refresher#handleRefreshResult(
   *     com.google.common.util.concurrent.ListenableFuture)
   */
  private ListenableFuture<InstanceData> startRefreshAttempt() {
    // As soon as we begin submitting refresh attempts to the executor, mark a refresh
    // as "in-progress" so that subsequent forceRefresh() calls balk until this one completes.
    synchronized (instanceDataGuard) {
      refreshRunning = true;
    }

    logger.fine(String.format("[%s] Refresh Operation: Acquiring rate limiter permit.", name));
    ListenableFuture<?> delay = rateLimiter.acquireAsync(executor);
    delay.addListener(
        () ->
            logger.fine(
                String.format("[%s] Refresh Operation: Rate limiter permit acquired.", name)),
        executor);

    // Once rate limiter is done, attempt to getInstanceData.
    ListenableFuture<InstanceData> dataFuture =
        Futures.whenAllComplete(delay).callAsync(refreshOperation::get, executor);

    // Finally, reschedule refresh after getInstanceData is complete.
    return Futures.whenAllComplete(dataFuture)
        .callAsync(() -> handleRefreshResult(dataFuture), executor);
  }

  private ListenableFuture<InstanceData> handleRefreshResult(
      ListenableFuture<InstanceData> dataFuture) {
    try {
      // This does not block, because it only gets called when dataFuture has completed.
      // This will throw an exception if the refresh attempt has failed.
      InstanceData data = dataFuture.get();

      logger.fine(
          String.format(
              "[%s] Refresh Operation: Completed refresh with new certificate expiration at %s.",
              name, data.getExpiration().toString()));
      long secondsToRefresh =
          refreshCalculator.calculateSecondsUntilNextRefresh(Instant.now(), data.getExpiration());

      logger.fine(
          String.format(
              "[%s] Refresh Operation: Next operation scheduled at %s.",
              name,
              Instant.now()
                  .plus(secondsToRefresh, ChronoUnit.SECONDS)
                  .truncatedTo(ChronoUnit.SECONDS)
                  .toString()));

      synchronized (instanceDataGuard) {
        // Refresh completed successfully, reset forceRefreshRunning.
        refreshRunning = false;
        currentRefreshFailure = null;
        currentInstanceData = Futures.immediateFuture(data);

        // Now update nextInstanceData to perform a refresh after the
        // scheduled delay
        nextInstanceData =
            Futures.scheduleAsync(
                this::startRefreshAttempt, secondsToRefresh, TimeUnit.SECONDS, executor);

        // Resolves to an T immediately
        return currentInstanceData;
      }

    } catch (ExecutionException | InterruptedException e) {
      logger.log(
          Level.FINE,
          String.format(
              "[%s] Refresh Operation: Failed! Starting next refresh operation immediately.", name),
          e);
      synchronized (instanceDataGuard) {
        currentRefreshFailure = e;
        nextInstanceData = this.startRefreshAttempt();

        // Resolves after the next successful refresh attempt.
        return nextInstanceData;
      }
    }
  }

  ListenableFuture<InstanceData> getNext() {
    synchronized (instanceDataGuard) {
      return this.nextInstanceData;
    }
  }

  ListenableFuture<InstanceData> getCurrent() {
    synchronized (instanceDataGuard) {
      return this.currentInstanceData;
    }
  }
}