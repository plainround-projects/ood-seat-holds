package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.Clock;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatTransitions;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.function.Function;

/**
 * The use cases. Each reads the seat, asks a pure transition what should happen, and writes the
 * result through the store's compare-and-set. If the write loses a race, it reads again and decides
 * again; a state that changed under it may make a different transition right, or none.
 */
public final class SeatHoldService implements HoldSeats, ConfirmHold, ExpireHolds {

  private static final Logger LOG = System.getLogger(SeatHoldService.class.getName());
  private static final int MAX_ATTEMPTS = 5;

  private final SeatStore store;
  private final Clock clock;
  private final Duration holdFor;

  /**
   * Creates the service.
   *
   * @param store where seats live
   * @param clock the time source
   * @param holdFor how long a hold lasts
   */
  public SeatHoldService(SeatStore store, Clock clock, Duration holdFor) {
    this.store = store;
    this.clock = clock;
    this.holdFor = holdFor;
  }

  @Override
  public Outcome hold(SeatId seat, CustomerId customer) {
    Outcome outcome =
        decideAndWrite(seat, current -> SeatTransitions.hold(current, customer, clock, holdFor));
    log("hold", seat, customer, outcome);
    return outcome;
  }

  @Override
  public Outcome confirm(SeatId seat, CustomerId customer) {
    Outcome outcome =
        decideAndWrite(seat, current -> SeatTransitions.confirm(current, customer, clock));
    log("confirm", seat, customer, outcome);
    return outcome;
  }

  @Override
  public int sweep() {
    int released = 0;
    for (SeatId seat : store.seats()) {
      if (release(seat) instanceof Success) {
        released++;
      }
    }
    return released;
  }

  @Override
  public Outcome release(SeatId seat) {
    Outcome outcome = decideAndWrite(seat, current -> SeatTransitions.release(current, clock));
    if (outcome instanceof Success) {
      LOG.log(Level.INFO, "event=released seat={0}", seat);
    }
    return outcome;
  }

  private Outcome decideAndWrite(SeatId seat, Function<SeatState, Outcome> transition) {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      SeatState current = store.get(seat);
      Outcome outcome = transition.apply(current);
      if (!(outcome instanceof Success success)) {
        return outcome;
      }
      if (store.update(seat, current, success.newState())) {
        return outcome;
      }
    }
    return new Reason("seat is contended, try again");
  }

  /** Successes are INFO; a refusal is routine under contention, so it is DEBUG (FINE). */
  private static void log(String action, SeatId seat, CustomerId customer, Outcome outcome) {
    if (outcome instanceof Reason reason) {
      LOG.log(
          Level.DEBUG,
          "event={0} seat={1} customer={2} outcome=refused reason=\"{3}\"",
          action,
          seat,
          customer,
          reason.message());
    } else {
      LOG.log(Level.INFO, "event={0} seat={1} customer={2} outcome=ok", action, seat, customer);
    }
  }
}
