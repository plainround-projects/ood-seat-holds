package io.plainround.seatholds.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Where a seat is in its life: free, held for one customer until a time, or sold. Data, not an
 * object with behavior: the transitions are pure functions in {@link SeatTransitions}, and the
 * guarantee that a seat is sold at most once belongs to {@link SeatStore#update}, not to these
 * records.
 */
public sealed interface SeatState permits SeatState.Free, SeatState.Held, SeatState.Sold {

  /** Nobody holds the seat and it has not been sold. */
  record Free() implements SeatState {}

  /**
   * One customer has the seat for a limited time.
   *
   * @param customer who holds it
   * @param expiresAt the last instant the hold can be confirmed, kept to the millisecond so every
   *     store, whatever its timestamp precision, compares equal values as equal
   */
  record Held(CustomerId customer, Instant expiresAt) implements SeatState {

    /**
     * Normalizes the expiry to milliseconds.
     *
     * @throws IllegalArgumentException if the customer or the expiry is missing
     */
    public Held {
      if (customer == null || expiresAt == null) {
        throw new IllegalArgumentException("a hold needs a customer and an expiry");
      }
      expiresAt = expiresAt.truncatedTo(ChronoUnit.MILLIS);
    }
  }

  /**
   * The seat is sold.
   *
   * @param customer who bought it
   */
  record Sold(CustomerId customer) implements SeatState {

    /**
     * Validates the buyer.
     *
     * @throws IllegalArgumentException if the customer is missing
     */
    public Sold {
      if (customer == null) {
        throw new IllegalArgumentException("a sale needs a customer");
      }
    }
  }
}
