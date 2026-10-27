package io.plainround.seatholds.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.UnknownSeatException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What any {@link SeatStore} must do, independent of how it is built. Each adapter's test class
 * extends this one and supplies a store; none of these tests changes between adapters, so passing
 * them is what makes an adapter trustworthy.
 */
public abstract class SeatStoreContract {

  private static final Instant EXPIRY = Instant.parse("2026-01-01T00:10:00Z");
  private static final CustomerId ADA = new CustomerId("ada");
  private static final CustomerId BRUNO = new CustomerId("bruno");

  private SeatStore store;
  private SeatId seat;

  /** A new, empty store of the adapter under test. */
  protected abstract SeatStore newStore();

  @BeforeEach
  void freshStoreWithOneFreeSeat() {
    store = newStore();
    seat = new SeatId("A1-" + System.nanoTime());
    store.add(seat);
  }

  @Test
  void aNewSeatIsFree() {
    assertThat(store.get(seat)).isEqualTo(new Free());
  }

  @Test
  void getAndUpdateRoundTripEveryKindOfState() {
    var held = new Held(ADA, EXPIRY);

    assertThat(store.update(seat, new Free(), held)).isTrue();
    assertThat(store.get(seat)).isEqualTo(held);

    assertThat(store.update(seat, held, new Sold(ADA))).isTrue();
    assertThat(store.get(seat)).isEqualTo(new Sold(ADA));
  }

  @Test
  void aHeldSeatCanGoBackToFree() {
    var held = new Held(ADA, EXPIRY);
    store.update(seat, new Free(), held);

    assertThat(store.update(seat, held, new Free())).isTrue();
    assertThat(store.get(seat)).isEqualTo(new Free());
  }

  @Test
  void aStaleExpectedStateIsRejectedAndNothingChanges() {
    var adaHold = new Held(ADA, EXPIRY);
    var first = store.update(seat, new Free(), adaHold);
    var second = store.update(seat, new Free(), new Held(BRUNO, EXPIRY));

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(store.get(seat)).isEqualTo(adaHold);
  }

  @Test
  void anExpectedStateOfTheRightKindButTheWrongValueIsRejected() {
    store.update(seat, new Free(), new Held(ADA, EXPIRY));

    assertThat(store.update(seat, new Held(BRUNO, EXPIRY), new Sold(BRUNO))).isFalse();
    assertThat(store.update(seat, new Held(ADA, EXPIRY.plusSeconds(1)), new Sold(ADA))).isFalse();
  }

  @Test
  void twoConcurrentUpdatesOfOneSeatExactlyOneSucceeds() throws Exception {
    for (int round = 0; round < 25; round++) {
      var contested = new SeatId("contested-" + round + "-" + System.nanoTime());
      store.add(contested);
      var start = new CountDownLatch(1);
      var winners = new AtomicInteger();
      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> both = new ArrayList<>();
        for (CustomerId customer : List.of(ADA, BRUNO)) {
          both.add(
              pool.submit(
                  () -> {
                    start.await();
                    if (store.update(contested, new Free(), new Held(customer, EXPIRY))) {
                      winners.incrementAndGet();
                    }
                    return null;
                  }));
        }
        start.countDown();
        for (Future<?> one : both) {
          one.get();
        }
      }
      assertThat(winners).as("round " + round).hasValue(1);
    }
  }

  @Test
  void anUnknownSeatIsAnError() {
    var unknown = new SeatId("nowhere");

    assertThatThrownBy(() -> store.get(unknown)).isInstanceOf(UnknownSeatException.class);
    assertThatThrownBy(() -> store.update(unknown, new Free(), new Sold(ADA)))
        .isInstanceOf(UnknownSeatException.class);
  }

  @Test
  void addingASeatTwiceKeepsItsState() {
    var held = new Held(ADA, EXPIRY);
    store.update(seat, new Free(), held);

    assertThat(store.add(seat)).isFalse();
    assertThat(store.get(seat)).isEqualTo(held);
  }

  @Test
  void seatsAreListedInIdOrder() {
    String prefix = "order-" + System.nanoTime() + "-";
    store.add(new SeatId(prefix + "b"));
    store.add(new SeatId(prefix + "a"));
    store.add(new SeatId(prefix + "c"));

    var all = store.seats();

    assertThat(all)
        .as("the whole list is sorted, whatever else the store holds")
        .isSortedAccordingTo(Comparator.comparing(SeatId::value));
    assertThat(all.stream().map(SeatId::value).filter(id -> id.startsWith(prefix)))
        .containsExactly(prefix + "a", prefix + "b", prefix + "c");
  }

  @Test
  void aStateReadBackIsEqualToTheOneWritten() {
    SeatState written = new Held(ADA, Instant.parse("2026-01-01T00:10:00.123456789Z"));
    store.update(seat, new Free(), written);

    assertThat(store.get(seat)).isEqualTo(written);
  }
}
