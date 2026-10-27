package io.plainround.seatholds.domain;

/**
 * Identifier of a seat; never blank.
 *
 * @param value the id, such as {@code s-9}
 */
public record SeatId(String value) {

  /**
   * Creates a seat id.
   *
   * @throws IllegalArgumentException if the value is null or blank
   */
  public SeatId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("seat id must not be blank");
    }
    value = value.strip();
  }

  @Override
  public String toString() {
    return value;
  }
}
