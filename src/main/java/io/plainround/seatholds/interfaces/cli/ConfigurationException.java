package io.plainround.seatholds.interfaces.cli;

/** An environment variable holds a value the program cannot use. */
public final class ConfigurationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which variable is wrong and what is expected
   */
  public ConfigurationException(String message) {
    super(message);
  }
}
