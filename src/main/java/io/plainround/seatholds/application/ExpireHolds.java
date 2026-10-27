package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: free holds whose time has run out. Called by a scheduler. */
public interface ExpireHolds {

  /**
   * Sweeps all seats once.
   *
   * @return how many holds were released
   */
  int sweep();

  /**
   * Releases one seat's hold if, and only if, it has expired.
   *
   * @param seat the seat
   * @return the freed seat, or why it was left alone
   */
  Outcome release(SeatId seat);
}
