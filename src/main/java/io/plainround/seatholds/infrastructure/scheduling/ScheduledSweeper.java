package io.plainround.seatholds.infrastructure.scheduling;

import io.plainround.seatholds.application.ExpireHolds;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the expiry sweep on a fixed delay and stops cleanly: {@link #close} lets a sweep in progress
 * finish, then ends the thread. A sweep that throws is logged and the next one still runs.
 */
public final class ScheduledSweeper implements AutoCloseable {

  private static final Logger LOG = System.getLogger(ScheduledSweeper.class.getName());
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "hold-sweeper");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * Starts sweeping.
   *
   * @param holds the use case to run
   * @param every the delay between the end of one sweep and the start of the next
   */
  public ScheduledSweeper(ExpireHolds holds, Duration every) {
    executor.scheduleWithFixedDelay(
        () -> sweepOnce(holds), every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static void sweepOnce(ExpireHolds holds) {
    try {
      int released = holds.sweep();
      LOG.log(Level.DEBUG, "event=sweep released={0}", released);
    } catch (RuntimeException failure) {
      LOG.log(Level.ERROR, "event=sweep_failed error={0}", failure.toString());
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
