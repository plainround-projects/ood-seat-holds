package io.plainround.seatholds.domain;

import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import java.time.Duration;

/**
 * The seat's state machine as pure functions: each takes the current state and returns the next
 * state or a reason. Nothing here reads a store, mutates anything or throws, so every transition is
 * testable with a value and a fixed clock.
 *
 * <p>The edges are free to held (hold), held to sold (confirm, before the expiry), and held to free
 * (release, after the expiry). The instant of the expiry itself belongs to the customer: a confirm
 * at that instant succeeds and a release is refused, so the two cannot both be right.
 */
public final class SeatTransitions {

  private SeatTransitions() {}

  /**
   * A customer asks to hold a seat.
   *
   * @param current the seat's state
   * @param customer who asks
   * @param clock the time source
   * @param holdFor how long the hold lasts
   * @return the hold, or why not
   */
  public static Outcome hold(
      SeatState current, CustomerId customer, Clock clock, Duration holdFor) {
    return switch (current) {
      case Free free -> new Success(new Held(customer, clock.now().plus(holdFor)));
      case Held held -> new Reason("seat is held");
      case Sold sold -> new Reason("already sold");
    };
  }

  /**
   * A customer confirms the hold they were given.
   *
   * @param current the seat's state
   * @param customer who confirms
   * @param clock the time source
   * @return the sale, or why not
   */
  public static Outcome confirm(SeatState current, CustomerId customer, Clock clock) {
    return switch (current) {
      case Held held
          when held.customer().equals(customer) && !clock.now().isAfter(held.expiresAt()) ->
          new Success(new Sold(customer));
      case Held held when held.customer().equals(customer) -> new Reason("hold expired");
      case Held held -> new Reason("held by someone else");
      case Free free -> new Reason("not held");
      case Sold sold -> new Reason("already sold");
    };
  }

  /**
   * The sweeper frees a hold whose time has run out.
   *
   * @param current the seat's state
   * @param clock the time source
   * @return the freed seat, or why not
   */
  public static Outcome release(SeatState current, Clock clock) {
    return switch (current) {
      case Held held when clock.now().isAfter(held.expiresAt()) -> new Success(new Free());
      case Held held -> new Reason("hold not expired");
      case Free free -> new Reason("not held");
      case Sold sold -> new Reason("already sold");
    };
  }
}
