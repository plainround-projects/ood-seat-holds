package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: a customer wants a seat held. Called by an adapter; never calls out to one. */
public interface HoldSeats {

  /**
   * Holds a seat for a customer.
   *
   * @param seat the seat
   * @param customer who wants it
   * @return the new state, or why the seat cannot be held
   */
  Outcome hold(SeatId seat, CustomerId customer);
}
