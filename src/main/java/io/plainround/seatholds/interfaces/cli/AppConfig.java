package io.plainround.seatholds.interfaces.cli;

import io.plainround.seatholds.infrastructure.jdbc.DatabaseSettings;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Settings read from the environment and validated once at start-up; see {@code .env.example}.
 *
 * @param store which adapter holds the seats
 * @param database where the database is; present only for the JDBC store
 * @param holdFor how long a hold lasts
 * @param sweepEvery the delay between expiry sweeps
 * @param seats how many seats {@code s-1} to {@code s-N} the store is given at start-up
 * @param logLevel the lowest log level written to standard error
 */
public record AppConfig(
    StoreKind store,
    DatabaseSettings database,
    Duration holdFor,
    Duration sweepEvery,
    int seats,
    String logLevel) {

  /** The seat store adapters. */
  public enum StoreKind {
    /** A concurrent map in this process. */
    MEMORY,
    /** PostgreSQL through JDBC. */
    JDBC
  }

  /**
   * Reads and validates the settings.
   *
   * @param env the environment variables
   * @return the validated settings
   * @throws ConfigurationException if a value is malformed or out of range
   */
  public static AppConfig from(Map<String, String> env) {
    try {
      StoreKind store =
          StoreKind.valueOf(env.getOrDefault("STORE", "memory").toUpperCase(Locale.ROOT));
      DatabaseSettings database =
          store == StoreKind.JDBC
              ? new DatabaseSettings(
                  env.get("DATABASE_URL"),
                  env.get("DATABASE_USER"),
                  env.get("DATABASE_PASSWORD"),
                  Integer.parseInt(env.getOrDefault("POOL_SIZE", "16")),
                  positive("CONNECTION_TIMEOUT", env.getOrDefault("CONNECTION_TIMEOUT", "PT30S")))
              : null;
      Duration holdFor = positive("HOLD_DURATION", env.getOrDefault("HOLD_DURATION", "PT10M"));
      Duration sweepEvery = positive("SWEEP_INTERVAL", env.getOrDefault("SWEEP_INTERVAL", "PT30S"));
      int seats = Integer.parseInt(env.getOrDefault("SEATS", "10"));
      if (seats < 1) {
        throw new IllegalArgumentException("SEATS must be at least 1, was " + seats);
      }
      String level = env.getOrDefault("LOG_LEVEL", "INFO").toUpperCase(Locale.ROOT);
      Logging.requireKnownLevel(level);
      return new AppConfig(store, database, holdFor, sweepEvery, seats, level);
    } catch (DateTimeParseException | IllegalArgumentException problem) {
      throw new ConfigurationException("invalid configuration: " + problem.getMessage());
    }
  }

  private static Duration positive(String name, String text) {
    Duration duration = Duration.parse(text);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(
          name + " must be a positive ISO-8601 duration, was " + text);
    }
    return duration;
  }
}
