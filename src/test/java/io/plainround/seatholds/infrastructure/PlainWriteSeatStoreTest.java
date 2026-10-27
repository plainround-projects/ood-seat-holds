package io.plainround.seatholds.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatTransitions;
import io.plainround.seatholds.domain.UnknownSeatException;
import io.plainround.seatholds.infrastructure.memory.PlainWriteSeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The counter-example: what the store contract exists to catch. */
class PlainWriteSeatStoreTest {

  private static final Instant EXPIRY = Instant.parse("2026-01-01T00:10:00Z");

  @Test
  void aStaleWriteSucceedsSoTwoCallersBothThinkTheyWonTheSeat() {
    var store = new PlainWriteSeatStore();
    var seat = new SeatId("s-9");
    store.add(seat);
    var adaRead = store.get(seat);
    var brunoRead = store.get(seat);

    var ada = store.update(seat, adaRead, new Held(new CustomerId("ada"), EXPIRY));
    var bruno = store.update(seat, brunoRead, new Held(new CustomerId("bruno"), EXPIRY));

    assertThat(ada).isTrue();
    assertThat(bruno).as("the stale write that a compare-and-set refuses").isTrue();
    assertThat(store.get(seat)).isEqualTo(new Held(new CustomerId("bruno"), EXPIRY));
  }

  @Test
  void theDomainDecidesRightBothTimesAndStillTheSeatIsLostBecauseOnlyTheStoreCouldStopIt() {
    var store = new PlainWriteSeatStore();
    var seat = new SeatId("s-1");
    store.add(seat);
    var clock = new MutableClock();
    var tenMinutes = Duration.ofMinutes(10);
    var adaSaw = store.get(seat);
    var brunoSaw = store.get(seat);

    var adaDecision = SeatTransitions.hold(adaSaw, new CustomerId("ada"), clock, tenMinutes);
    var brunoDecision = SeatTransitions.hold(brunoSaw, new CustomerId("bruno"), clock, tenMinutes);

    assertThat(adaDecision).isInstanceOf(Success.class);
    assertThat(brunoDecision).isInstanceOf(Success.class);
    assertThat(store.update(seat, adaSaw, ((Success) adaDecision).newState())).isTrue();
    assertThat(store.update(seat, brunoSaw, ((Success) brunoDecision).newState())).isTrue();
  }

  @Test
  void anUnknownSeatIsStillAnError() {
    var store = new PlainWriteSeatStore();

    assertThatThrownBy(() -> store.get(new SeatId("x"))).isInstanceOf(UnknownSeatException.class);
    assertThatThrownBy(() -> store.update(new SeatId("x"), new Free(), new Free()))
        .isInstanceOf(UnknownSeatException.class);
    assertThat(store.add(new SeatId("x"))).isTrue();
    assertThat(store.add(new SeatId("x"))).isFalse();
    assertThat(store.seats()).containsExactly(new SeatId("x"));
  }
}
