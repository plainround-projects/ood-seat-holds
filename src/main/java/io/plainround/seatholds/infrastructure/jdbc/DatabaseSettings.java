package io.plainround.seatholds.infrastructure.jdbc;

import java.time.Duration;

/**
 * Where the database is and how many connections to open. The password comes from the environment
 * and is never printed: {@link #toString} leaves it out.
 *
 * @param url the JDBC URL, such as {@code jdbc:postgresql://localhost:55468/seatholds}
 * @param user the database user
 * @param password the database password
 * @param poolSize the most connections to open, at least one
 * @param connectionTimeout how long a caller waits for a free connection before the call fails
 */
public record DatabaseSettings(
    String url, String user, String password, int poolSize, Duration connectionTimeout) {

  /**
   * Validates the settings.
   *
   * @throws IllegalArgumentException if a part is missing or the pool is empty
   */
  public DatabaseSettings {
    if (url == null || !url.startsWith("jdbc:postgresql:")) {
      throw new IllegalArgumentException("DATABASE_URL must be a jdbc:postgresql: URL");
    }
    if (user == null || user.isBlank() || password == null) {
      throw new IllegalArgumentException("DATABASE_USER and DATABASE_PASSWORD are required");
    }
    if (poolSize < 1) {
      throw new IllegalArgumentException("POOL_SIZE must be at least 1, was " + poolSize);
    }
    if (connectionTimeout == null || connectionTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException("CONNECTION_TIMEOUT must be at least PT1S");
    }
  }

  @Override
  public String toString() {
    return "DatabaseSettings[url="
        + url
        + ", user="
        + user
        + ", poolSize="
        + poolSize
        + ", connectionTimeout="
        + connectionTimeout
        + "]";
  }
}
