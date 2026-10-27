package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: a customer turns their hold into a sale. */
public interface ConfirmHold {

  /**
   * Confirms a hold.
   *
   * @param seat the seat
   * @param customer who holds it
   * @return the sold state, or why it cannot be confirmed
   */
  Outcome confirm(SeatId seat, CustomerId customer);
}
