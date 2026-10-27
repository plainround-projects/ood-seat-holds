package io.plainround.seatholds.domain;

/** The store could not be reached or answered with an error; the seat's state is unknown. */
public final class SeatStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the store was asked to do
   * @param cause the underlying failure
   */
  public SeatStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
