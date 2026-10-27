package io.plainround.seatholds.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.simulation.HoldRace;
import io.plainround.seatholds.application.simulation.RaceResult;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The race over the in-memory adapter; the JDBC adapter runs the same one in JdbcHoldRaceIT. */
class HoldRaceTest {

  private final InMemorySeatStore store = new InMemorySeatStore();

  private List<SeatId> tenFreeSeats(String label) {
    List<SeatId> seats =
        IntStream.rangeClosed(1, 10).mapToObj(n -> new SeatId(label + "-s" + n)).toList();
    seats.forEach(store::add);
    return seats;
  }

  private RaceResult race(List<SeatId> seats, int customers) {
    var service = new SeatHoldService(store, new MutableClock(), Duration.ofMinutes(10));
    return new HoldRace(service).run(seats, customers);
  }

  @Test
  void aThousandCustomersRaceForTenSeatsAndExactlyTenWinInEveryOneOfTwentyRuns() {
    for (int run = 1; run <= 20; run++) {
      var result = race(tenFreeSeats("run" + run), 1_000);

      assertThat(result).as("run %d", run).isEqualTo(new RaceResult(10, 990));
    }
  }

  @Test
  void everySeatEndsHeldByExactlyOneCustomerAndNoCustomerHoldsTwo() {
    var seats = tenFreeSeats("single");

    race(seats, 1_000);

    var holders = seats.stream().map(seat -> ((Held) store.get(seat)).customer()).toList();
    assertThat(holders).doesNotHaveDuplicates().hasSize(10);
  }

  @Test
  void fewerCustomersThanSeatsAllWin() {
    assertThat(race(tenFreeSeats("few"), 4)).isEqualTo(new RaceResult(4, 0));
  }

  @Test
  void theResultPrintsTheWayTheLessonShowsIt() {
    assertThat(new RaceResult(10, 990)).hasToString("wins=10 losses=990");
  }
}
