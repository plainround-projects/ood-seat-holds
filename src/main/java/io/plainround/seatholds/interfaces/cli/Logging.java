package io.plainround.seatholds.interfaces.cli;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Sends the JDK system logger's output to standard error as one {@code key=value} line each. */
final class Logging {

  private static final List<String> LEVELS = List.of("SEVERE", "WARNING", "INFO", "FINE");

  private Logging() {}

  static void requireKnownLevel(String level) {
    if (!LEVELS.contains(level)) {
      throw new IllegalArgumentException("LOG_LEVEL must be one of " + LEVELS + ", was " + level);
    }
  }

  static void configure(String level) {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format", "ts=%1$tFT%1$tT.%1$tLZ level=%4$s %5$s%n");
    LogManager.getLogManager().reset();
    Logger root = Logger.getLogger("");
    root.addHandler(new java.util.logging.ConsoleHandler());
    Level parsed = Level.parse(level);
    root.setLevel(parsed);
    for (Handler handler : root.getHandlers()) {
      handler.setLevel(parsed);
    }
  }
}
