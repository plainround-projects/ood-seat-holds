package io.plainround.seatholds.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeatTransitionsTest {

  private static final CustomerId ADA = new CustomerId("ada");
  private static final CustomerId BRUNO = new CustomerId("bruno");
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

  private final MutableClock clock = new MutableClock();

  private Held heldByAdaUntilTenMinutesFromNow() {
    return new Held(ADA, clock.now().plus(TEN_MINUTES));
  }

  @Test
  void aFreeSeatCanBeHeldForTenMinutes() {
    var outcome = SeatTransitions.hold(new Free(), ADA, clock, TEN_MINUTES);

    assertThat(outcome).isEqualTo(new Success(new Held(ADA, MutableClock.START.plus(TEN_MINUTES))));
  }

  @Test
  void aHeldSeatCannotBeHeldAgain() {
    var outcome =
        SeatTransitions.hold(heldByAdaUntilTenMinutesFromNow(), BRUNO, clock, TEN_MINUTES);

    assertThat(outcome).isEqualTo(new Reason("seat is held"));
  }

  @Test
  void aSoldSeatCannotBeHeld() {
    assertThat(SeatTransitions.hold(new Sold(ADA), BRUNO, clock, TEN_MINUTES))
        .isEqualTo(new Reason("already sold"));
  }

  @Test
  void theHolderConfirmingBeforeTheExpirySellsTheSeat() {
    var outcome = SeatTransitions.confirm(heldByAdaUntilTenMinutesFromNow(), ADA, clock);

    assertThat(outcome).isEqualTo(new Success(new Sold(ADA)));
  }

  @Test
  void confirmingAnExpiredHoldGivesAReasonNotASale() {
    var expiredHold = new Held(ADA, clock.now().minus(Duration.ofMinutes(1)));

    var outcome = SeatTransitions.confirm(expiredHold, ADA, clock);

    assertThat(outcome).isEqualTo(new Reason("hold expired"));
  }

  @Test
  void aConfirmAtTheExactExpiryInstantStillSucceeds() {
    var hold = new Held(ADA, clock.now());

    assertThat(SeatTransitions.confirm(hold, ADA, clock)).isEqualTo(new Success(new Sold(ADA)));
  }

  @Test
  void aConfirmOneMillisecondAfterTheExpiryFails() {
    var hold = new Held(ADA, clock.now());
    clock.advance(Duration.ofMillis(1));

    assertThat(SeatTransitions.confirm(hold, ADA, clock)).isEqualTo(new Reason("hold expired"));
  }

  @Test
  void someoneElseCannotConfirmTheHold() {
    assertThat(SeatTransitions.confirm(heldByAdaUntilTenMinutesFromNow(), BRUNO, clock))
        .isEqualTo(new Reason("held by someone else"));
  }

  @Test
  void anExpiredHoldCannotBeConfirmedBySomeoneElseEither() {
    var expired = new Held(ADA, clock.now().minusSeconds(5));

    assertThat(SeatTransitions.confirm(expired, BRUNO, clock))
        .isEqualTo(new Reason("held by someone else"));
  }

  @Test
  void aFreeSeatCannotBeConfirmedAndASoldSeatCannotBeConfirmedAgain() {
    assertThat(SeatTransitions.confirm(new Free(), ADA, clock)).isEqualTo(new Reason("not held"));
    assertThat(SeatTransitions.confirm(new Sold(ADA), ADA, clock))
        .isEqualTo(new Reason("already sold"));
  }

  @Test
  void anExpiredHoldCanBeReleased() {
    var expired = new Held(ADA, clock.now().minusSeconds(1));

    assertThat(SeatTransitions.release(expired, clock)).isEqualTo(new Success(new Free()));
  }

  @Test
  void aHoldThatHasNotExpiredCannotBeReleasedNotEvenAtTheExactExpiryInstant() {
    assertThat(SeatTransitions.release(new Held(ADA, clock.now()), clock))
        .isEqualTo(new Reason("hold not expired"));
    assertThat(SeatTransitions.release(heldByAdaUntilTenMinutesFromNow(), clock))
        .isEqualTo(new Reason("hold not expired"));
  }

  @Test
  void aFreeOrSoldSeatCannotBeReleased() {
    assertThat(SeatTransitions.release(new Free(), clock)).isEqualTo(new Reason("not held"));
    assertThat(SeatTransitions.release(new Sold(ADA), clock)).isEqualTo(new Reason("already sold"));
  }

  @Test
  void confirmAndReleaseNeverBothSucceedForOneStateAtOneInstant() {
    for (long offsetMillis = -2; offsetMillis <= 2; offsetMillis++) {
      var hold = new Held(ADA, Instant.parse("2026-01-01T00:10:00Z"));
      long offset = offsetMillis;
      var at = (Clock) () -> hold.expiresAt().plusMillis(offset);

      boolean confirms = SeatTransitions.confirm(hold, ADA, at) instanceof Success;
      boolean releases = SeatTransitions.release(hold, at) instanceof Success;

      assertThat(confirms ^ releases).as("offset %d ms", offsetMillis).isTrue();
    }
  }
}
