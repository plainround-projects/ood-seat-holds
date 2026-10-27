package io.plainround.seatholds.interfaces.cli;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Entry point of the {@code seatholds} command. */
public final class Main {

  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(15);

  private Main() {}

  /**
   * Runs the command and exits with its code. SIGINT and SIGTERM make {@code serve} stop: the
   * signal's shutdown hook releases it and then waits for the main thread, so the sweep in progress
   * finishes, the pool closes and the exit code is the command's own, not 143.
   *
   * @param args the command line, see {@link Cli}
   */
  public static void main(String[] args) {
    CountDownLatch shutdown = new CountDownLatch(1);
    Thread main = Thread.currentThread();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  shutdown.countDown();
                  try {
                    main.join(SHUTDOWN_WAIT.toMillis());
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                },
                "shutdown-signal"));
    int exitCode = new Cli(System.getenv(), System.out, System.err, shutdown).run(args);
    System.out.flush();
    Runtime.getRuntime().halt(exitCode);
  }
}
