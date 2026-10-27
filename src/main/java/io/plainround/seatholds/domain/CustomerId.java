package io.plainround.seatholds.domain;

/**
 * Identifier of a customer; never blank.
 *
 * @param value the id, such as {@code ada}
 */
public record CustomerId(String value) {

  /**
   * Creates a customer id.
   *
   * @throws IllegalArgumentException if the value is null or blank
   */
  public CustomerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("customer id must not be blank");
    }
    value = value.strip();
  }

  @Override
  public String toString() {
    return value;
  }
}
