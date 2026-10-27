package io.plainround.seatholds.interfaces.cli;

import java.util.concurrent.CountDownLatch;

/** Entry point of the {@code seatholds} command. */
public final class Main {

  private Main() {}

  /**
   * Runs the command and exits with its code. SIGINT and SIGTERM let {@code serve} stop cleanly.
   *
   * @param args the command line, see {@link Cli}
   */
  public static void main(String[] args) {
    CountDownLatch shutdown = new CountDownLatch(1);
    Thread stopServing = new Thread(shutdown::countDown, "shutdown-signal");
    Runtime.getRuntime().addShutdownHook(stopServing);
    int exitCode = new Cli(System.getenv(), System.out, System.err, shutdown).run(args);
    System.out.flush();
    Runtime.getRuntime().halt(exitCode);
  }
}
