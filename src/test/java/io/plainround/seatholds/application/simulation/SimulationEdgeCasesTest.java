package io.plainround.seatholds.application.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.application.simulation.SweepConfirmRace.Repetition;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatStoreException;
import io.plainround.seatholds.domain.UnknownSeatException;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The harness's own behavior: how it judges a repetition and how it fails. */
class SimulationEdgeCasesTest {

  private static final Instant SOME_TIME = Instant.parse("2026-01-01T00:10:00Z");
  private static final CustomerId ADA = new CustomerId("ada");

  @Test
  void aRepetitionIsConsistentOnlyWhenTheSeatAndBothReportsAgree() {
    assertThat(new Repetition(new Sold(ADA), true, 0).isConsistent()).isTrue();
    assertThat(new Repetition(new Free(), false, 1).isConsistent()).isTrue();

    assertThat(new Repetition(new Sold(ADA), true, 1).isConsistent())
        .as("sold and released")
        .isFalse();
    assertThat(new Repetition(new Sold(ADA), false, 0).isConsistent())
        .as("sold, not confirmed")
        .isFalse();
    assertThat(new Repetition(new Free(), true, 1).isConsistent())
        .as("freed and confirmed")
        .isFalse();
    assertThat(new Repetition(new Free(), false, 0).isConsistent()).as("nobody won").isFalse();
    assertThat(new Repetition(new Held(ADA, SOME_TIME), false, 0).isConsistent())
        .as("a third state")
        .isFalse();
  }

  @Test
  void theRendezvousStoreForwardsEveryOperation() {
    var inner = new InMemorySeatStore();
    var store = new RendezvousSeatStore(inner, 1);
    var seat = new SeatId("s-1");

    assertThat(store.add(seat)).isTrue();
    assertThat(store.seats()).containsExactly(seat);
    assertThat(store.get(seat)).isEqualTo(new Free());
    assertThat(store.update(seat, new Free(), new Sold(ADA))).isTrue();
    assertThat(inner.get(seat)).isEqualTo(new Sold(ADA));
  }

  @Test
  void theRendezvousStoreGivesUpWhenTheOtherParticipantNeverReads() {
    var inner = new InMemorySeatStore();
    inner.add(new SeatId("s-1"));
    var store = new RendezvousSeatStore(inner, 2, Duration.ofMillis(50));

    assertThatThrownBy(() -> store.get(new SeatId("s-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never read");
  }

  @Test
  void aRaceOverASeatTheStoreDoesNotKnowReportsTheCustomersFailure() {
    var service =
        new SeatHoldService(new InMemorySeatStore(), new MutableClock(), Duration.ofMinutes(10));

    assertThatThrownBy(() -> new HoldRace(service).run(List.of(new SeatId("missing")), 3))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(UnknownSeatException.class);
  }

  @Test
  void anInterruptedRaceKeepsTheInterruptFlagAndSaysSo() {
    var store = new InMemorySeatStore();
    var seat = new SeatId("s-1");
    store.add(seat);
    var service = new SeatHoldService(store, new MutableClock(), Duration.ofMinutes(10));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> new HoldRace(service).run(List.of(seat), 2))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("race interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aSweepRaceOverAStoreThatFailsReportsThatTheRaceDidNotFinish() {
    SeatStore broken = new FailingUpdates(new InMemorySeatStore());

    assertThatThrownBy(() -> new SweepConfirmRace(broken).run(new SeatId("s-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not finish");
  }

  @Test
  void aSweepRaceThatIsInterruptedKeepsTheInterruptFlagAndSaysSo() {
    var race = new SweepConfirmRace(new InMemorySeatStore());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> race.run(new SeatId("s-1")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("race interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aStoreFailureKeepsItsCause() {
    var cause = new IllegalStateException("connection refused");

    var failure = new SeatStoreException("reading seat s-1", cause);

    assertThat(failure).hasMessage("reading seat s-1").hasCause(cause);
  }

  /** A store whose writes fail, as a database that has gone away would. */
  private static final class FailingUpdates implements SeatStore {
    private final SeatStore delegate;

    FailingUpdates(SeatStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public SeatState get(SeatId seat) {
      return delegate.get(seat);
    }

    @Override
    public boolean update(SeatId seat, SeatState expected, SeatState next) {
      if (next instanceof Held) {
        return delegate.update(seat, expected, next);
      }
      throw new SeatStoreException("database is down", null);
    }

    @Override
    public boolean add(SeatId seat) {
      return delegate.add(seat);
    }

    @Override
    public List<SeatId> seats() {
      return delegate.seats();
    }
  }
}
