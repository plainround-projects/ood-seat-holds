package io.plainround.seatholds.domain;

import java.util.List;

/**
 * Outbound port: where seat states live. This is where "a seat is sold at most once" is enforced:
 * {@link #update} is a compare-and-set, so a write based on stale data cannot win. Every adapter
 * must pass the same contract test.
 */
public interface SeatStore {

  /**
   * Reads a seat's current state.
   *
   * @param seat the seat
   * @return its state
   * @throws UnknownSeatException if the seat was never added
   */
  SeatState get(SeatId seat);

  /**
   * Moves a seat to a new state only if it is still in the state the caller read.
   *
   * @param seat the seat
   * @param expected the state the caller based its decision on
   * @param next the state to move to
   * @return true only if the seat was in {@code expected} and {@code next} was applied; false if
   *     someone else changed it first
   * @throws UnknownSeatException if the seat was never added
   */
  boolean update(SeatId seat, SeatState expected, SeatState next);

  /**
   * Adds a seat in the free state unless it already exists.
   *
   * @param seat the seat
   * @return true if it was added, false if it was already there
   */
  boolean add(SeatId seat);

  /**
   * Every seat the store knows, in id order.
   *
   * @return an immutable list
   */
  List<SeatId> seats();
}
