package io.plainround.seatholds.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.application.simulation.HoldRace;
import io.plainround.seatholds.application.simulation.RaceResult;
import io.plainround.seatholds.application.simulation.SweepConfirmRace;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.infrastructure.time.SystemClock;
import io.plainround.seatholds.support.PostgresFixture;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The lesson's two races against PostgreSQL: a thousand virtual threads, then confirm vs sweep. */
class JdbcHoldRaceIT {

  private final JdbcSeatStore store = new JdbcSeatStore(PostgresFixture.pool());

  @Test
  void aThousandCustomersRaceForTenSeatsAndExactlyTenWinInEveryOneOfTwentyRuns() {
    var service = new SeatHoldService(store, new SystemClock(), Duration.ofMinutes(10));
    String batch = Long.toString(System.nanoTime(), 36);
    for (int run = 1; run <= 20; run++) {
      int number = run;
      List<SeatId> seats =
          IntStream.rangeClosed(1, 10)
              .mapToObj(n -> new SeatId("jdbc-" + batch + "-" + number + "-" + n))
              .toList();
      seats.forEach(store::add);

      var result = new HoldRace(service).run(seats, 1_000);

      assertThat(result).as("run %d", run).isEqualTo(new RaceResult(10, 990));
    }
  }

  @Test
  void acrossOneHundredRepetitionsASeatIsNeverBothSoldAndReleased() {
    var race = new SweepConfirmRace(store);
    String batch = Long.toString(System.nanoTime(), 36);

    for (int rep = 1; rep <= 100; rep++) {
      var result = race.run(new SeatId("sweep-" + batch + "-" + rep));

      assertThat(result.isConsistent())
          .as(
              "rep %d ended as %s, confirmed=%s, released=%d",
              rep, result.finalState(), result.confirmed(), result.released())
          .isTrue();
    }
  }
}
