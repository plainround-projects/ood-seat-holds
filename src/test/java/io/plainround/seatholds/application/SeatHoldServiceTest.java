package io.plainround.seatholds.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeatHoldServiceTest {

  private static final SeatId SEAT = new SeatId("s-9");
  private static final CustomerId ADA = new CustomerId("ada");
  private static final CustomerId BRUNO = new CustomerId("bruno");
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

  private final MutableClock clock = new MutableClock();
  private SeatStore store;
  private SeatHoldService service;

  @BeforeEach
  void oneFreeSeat() {
    store = new InMemorySeatStore();
    store.add(SEAT);
    service = new SeatHoldService(store, clock, TEN_MINUTES);
  }

  @Test
  void holdingAFreeSeatHoldsItForTenMinutes() {
    var outcome = service.hold(SEAT, ADA);

    assertThat(outcome).isEqualTo(new Success(new Held(ADA, MutableClock.START.plus(TEN_MINUTES))));
    assertThat(store.get(SEAT)).isEqualTo(new Held(ADA, MutableClock.START.plus(TEN_MINUTES)));
  }

  @Test
  void aSecondCustomerIsToldTheSeatIsHeld() {
    service.hold(SEAT, ADA);

    assertThat(service.hold(SEAT, BRUNO)).isEqualTo(new Reason("seat is held"));
  }

  @Test
  void confirmingWithinTenMinutesSellsTheSeat() {
    service.hold(SEAT, ADA);
    clock.advance(Duration.ofMinutes(9));

    assertThat(service.confirm(SEAT, ADA)).isEqualTo(new Success(new Sold(ADA)));
    assertThat(store.get(SEAT)).isEqualTo(new Sold(ADA));
  }

  @Test
  void confirmingAfterTenMinutesGivesAReasonAndSellsNothing() {
    service.hold(SEAT, ADA);
    clock.advance(Duration.ofMinutes(11));

    assertThat(service.confirm(SEAT, ADA)).isEqualTo(new Reason("hold expired"));
    assertThat(store.get(SEAT)).isInstanceOf(Held.class);
  }

  @Test
  void aSweepFreesOnlyTheHoldsThatHaveExpired() {
    var second = new SeatId("s-10");
    store.add(second);
    service.hold(SEAT, ADA);
    clock.advance(Duration.ofMinutes(6));
    service.hold(second, BRUNO);
    clock.advance(Duration.ofMinutes(6));

    int released = service.sweep();

    assertThat(released).isEqualTo(1);
    assertThat(store.get(SEAT)).isEqualTo(new Free());
    assertThat(store.get(second)).isInstanceOf(Held.class);
  }

  @Test
  void aSeatFreedBySweepingCanBeHeldAgain() {
    service.hold(SEAT, ADA);
    clock.advance(Duration.ofMinutes(11));
    service.sweep();

    assertThat(service.hold(SEAT, BRUNO)).isInstanceOf(Success.class);
  }

  @Test
  void aSweepLeavesSoldSeatsAlone() {
    service.hold(SEAT, ADA);
    service.confirm(SEAT, ADA);
    clock.advance(Duration.ofDays(1));

    assertThat(service.sweep()).isZero();
    assertThat(store.get(SEAT)).isEqualTo(new Sold(ADA));
  }

  @Test
  void releasingOneSeatDecidesByTheSameRules() {
    service.hold(SEAT, ADA);

    assertThat(service.release(SEAT)).isEqualTo(new Reason("hold not expired"));
    clock.advance(Duration.ofMinutes(11));
    assertThat(service.release(SEAT)).isEqualTo(new Success(new Free()));
  }

  @Test
  void aLostWriteIsReadAgainAndDecidedAgain() {
    var interfering =
        new LosesTheFirstWrite(store, new Held(BRUNO, MutableClock.START.plus(TEN_MINUTES)));
    var racing = new SeatHoldService(interfering, clock, TEN_MINUTES);

    var outcome = racing.hold(SEAT, ADA);

    assertThat(outcome)
        .as("bruno got there between ada's read and write")
        .isEqualTo(new Reason("seat is held"));
    assertThat(store.get(SEAT)).isEqualTo(new Held(BRUNO, MutableClock.START.plus(TEN_MINUTES)));
  }

  @Test
  void aSeatThatKeepsChangingUnderTheCallerEndsInAClearRefusalNotALoop() {
    var alwaysLoses = new LosesEveryWrite(store);
    var racing = new SeatHoldService(alwaysLoses, clock, TEN_MINUTES);

    assertThat(racing.hold(SEAT, ADA)).isEqualTo(new Reason("seat is contended, try again"));
    assertThat(alwaysLoses.attempts()).isEqualTo(5);
  }

  /** Another customer's hold lands between the service's read and its write, the first time. */
  private static final class LosesTheFirstWrite extends ForwardingStore {
    private final SeatState intruder;
    private boolean intruded;

    LosesTheFirstWrite(SeatStore delegate, SeatState intruder) {
      super(delegate);
      this.intruder = intruder;
    }

    @Override
    public boolean update(SeatId seat, SeatState expected, SeatState next) {
      if (!intruded) {
        intruded = true;
        delegate.update(seat, expected, intruder);
      }
      return delegate.update(seat, expected, next);
    }
  }

  private static final class LosesEveryWrite extends ForwardingStore {
    private final AtomicInteger attempts = new AtomicInteger();

    LosesEveryWrite(SeatStore delegate) {
      super(delegate);
    }

    @Override
    public boolean update(SeatId seat, SeatState expected, SeatState next) {
      attempts.incrementAndGet();
      return false;
    }

    int attempts() {
      return attempts.get();
    }
  }

  private abstract static class ForwardingStore implements SeatStore {
    final SeatStore delegate;

    ForwardingStore(SeatStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public SeatState get(SeatId seat) {
      return delegate.get(seat);
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
