package io.plainround.seatholds.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.interfaces.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The lesson's scenarios through the command a reader runs with {@code make run}. */
class ScenarioEndToEndTest {

  private static String output;

  @BeforeAll
  static void runTheScenario() {
    var out = new ByteArrayOutputStream();
    int exitCode =
        new Cli(
                Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new CountDownLatch(0))
            .run("scenario");
    assertThat(exitCode).as("every invariant held").isZero();
    output = out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void confirmingAnExpiredHoldGivesAReasonNeverASuccess() {
    assertThat(output)
        .startsWith("confirm expired hold -> Reason[message=hold expired]  (never Success)\n");
  }

  @Test
  void theSecondUpdateWithAStaleExpectedStateIsRejected() {
    assertThat(output).contains("first update: true  second update (stale expected): false\n");
  }

  @Test
  void everyOneOfFiveRacesHasExactlyTenWinnersAndNineHundredNinetyRejections() {
    assertThat(output.lines().filter(line -> line.equals("race result: wins=10 losses=990")))
        .hasSize(5);
  }

  @Test
  void theSweepVersusConfirmRaceNeverEndsInconsistent() {
    assertThat(output).contains("inconsistent=0");
    assertThat(output).contains("never both, never a third state");
  }

  @Test
  void aPlainWriteInsteadOfCompareAndSetSellsTheSeatTwice() {
    assertThat(output)
        .contains(
            "first update: true  second update (stale expected): true  <- both were told they won seat s-9");
  }
}
