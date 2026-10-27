package io.plainround.seatholds.domain;

/** The caller named a seat the store has never been given. */
public final class UnknownSeatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param seat the unknown seat
   */
  public UnknownSeatException(SeatId seat) {
    super("unknown seat: " + seat);
  }
}
