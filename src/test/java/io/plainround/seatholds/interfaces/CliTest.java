package io.plainround.seatholds.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.interfaces.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CliTest {

  private record Run(int exitCode, String out, String err) {}

  private static Run run(Map<String, String> env, CountDownLatch shutdown, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        new Cli(
                env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                shutdown)
            .run(args);
    return new Run(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static Run run(String... args) {
    return run(Map.of(), new CountDownLatch(0), args);
  }

  @Test
  void noArgumentsPrintTheUsage() {
    var result = run();

    assertThat(result.exitCode()).isZero();
    assertThat(result.out()).contains("usage: seatholds");
  }

  @Test
  void anUnknownCommandExitsWithTwo() {
    assertThat(run("dance").exitCode()).isEqualTo(2);
  }

  @Test
  void badSettingsExitWithTwoAndSayWhy() {
    var result = run(Map.of("SEATS", "0"), new CountDownLatch(0), "health");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.err()).contains("SEATS");
  }

  @Test
  void healthReportsTheStoreAndTheSeats() {
    var result = run("health");

    assertThat(result.exitCode()).isZero();
    assertThat(result.out()).isEqualTo("ok store=memory seats=10\n");
  }

  @Test
  void statusListsEverySeatAsFreeAtStartUp() {
    var result = run(Map.of("SEATS", "3"), new CountDownLatch(0), "status");

    assertThat(result.out()).isEqualTo("s-1 Free[]\ns-2 Free[]\ns-3 Free[]\n");
  }

  @Test
  void holdAndConfirmNeedASeatAndACustomer() {
    assertThat(run("hold", "s-1").exitCode()).isEqualTo(2);
    assertThat(run("confirm").exitCode()).isEqualTo(2);
  }

  @Test
  void holdingASeatReportsTheNewState() {
    var result = run("hold", "s-1", "ada");

    assertThat(result.exitCode()).isZero();
    assertThat(result.out()).startsWith("Success[newState=Held[customer=ada, expiresAt=");
  }

  @Test
  void confirmingASeatNobodyHeldIsARefusalWithExitCodeOne() {
    var result = run("confirm", "s-1", "ada");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.out()).isEqualTo("Reason[message=not held]\n");
  }

  @Test
  void anUnknownSeatIsReportedOnStandardError() {
    var result = run("hold", "s-99", "ada");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.err()).contains("unknown seat: s-99");
  }

  @Test
  void sweepReportsHowManyHoldsItReleased() {
    assertThat(run("sweep").out()).isEqualTo("released 0 expired hold(s)\n");
  }

  @Test
  void aSmallRacePrintsOneResultPerRun() {
    var result = run("race", "2", "50");

    assertThat(result.exitCode()).isZero();
    assertThat(result.out())
        .isEqualTo("race result: wins=10 losses=40\nrace result: wins=10 losses=40\n");
  }

  @Test
  void serveSweepsUntilTheShutdownSignalThenStopsCleanly() throws InterruptedException {
    var shutdown = new CountDownLatch(1);
    var env = Map.of("SWEEP_INTERVAL", "PT0.05S");
    var result = new Run[1];
    var server = new Thread(() -> result[0] = run(env, shutdown, "serve"));
    server.start();
    Thread.sleep(300);

    shutdown.countDown();
    server.join(10_000);

    assertThat(server.isAlive()).isFalse();
    assertThat(result[0].exitCode()).isZero();
    assertThat(result[0].out()).contains("serving:").contains("stopping:");
  }
}
