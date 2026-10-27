package io.plainround.seatholds.infrastructure.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Builds the connection pool. The pool is the concurrency limit: a thousand callers share it. */
public final class PostgresDataSource {

  private static final long STARTUP_RETRY_MILLIS = 30_000;

  private PostgresDataSource() {}

  /**
   * Opens a pool. If the database is still starting, the pool keeps trying for 30 seconds before
   * giving up, and every request for a connection waits at most the configured connection timeout,
   * then fails with an exception rather than queueing forever.
   *
   * @param settings where the database is
   * @return a pool the caller must close
   */
  public static HikariDataSource open(DatabaseSettings settings) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.url());
    config.setUsername(settings.user());
    config.setPassword(settings.password());
    config.setMaximumPoolSize(settings.poolSize());
    config.setConnectionTimeout(settings.connectionTimeout().toMillis());
    config.setInitializationFailTimeout(STARTUP_RETRY_MILLIS);
    config.setPoolName("seat-holds");
    return new HikariDataSource(config);
  }
}
