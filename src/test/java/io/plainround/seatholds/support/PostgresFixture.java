package io.plainround.seatholds.support;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatholds.infrastructure.jdbc.DatabaseSettings;
import io.plainround.seatholds.infrastructure.jdbc.PostgresDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL in a container for the whole test run, with the repository's own {@code
 * sql/001_schema.sql} applied, so the tests exercise the same DDL a reader runs with {@code make
 * db}. The image is {@code postgres:18} unless {@code POSTGRES_IMAGE} says otherwise.
 */
public final class PostgresFixture {

  private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(System.getenv().getOrDefault("POSTGRES_IMAGE", "postgres:18")))
          .withStartupTimeout(Duration.ofMinutes(5));

  private static HikariDataSource pool;

  private PostgresFixture() {}

  /** The shared pool, created on first use with the schema applied. */
  public static synchronized HikariDataSource pool() {
    if (pool == null) {
      pool = PostgresDataSource.open(settings());
      applySchema();
      Runtime.getRuntime().addShutdownHook(new Thread(pool::close, "close-test-pool"));
    }
    return pool;
  }

  private static DatabaseSettings settings() {
    if (EXTERNAL_URL != null) {
      return new DatabaseSettings(
          EXTERNAL_URL,
          System.getenv().getOrDefault("TEST_DATABASE_USER", "seatholds"),
          System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "seatholds-local-only"),
          16,
          Duration.ofMinutes(3));
    }
    POSTGRES.start();
    return new DatabaseSettings(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword(),
        16,
        Duration.ofMinutes(3));
  }

  private static void applySchema() {
    try (Connection connection = pool.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(Files.readString(Path.of("sql", "001_schema.sql")));
    } catch (SQLException | IOException failure) {
      throw new IllegalStateException("cannot apply sql/001_schema.sql", failure);
    }
  }
}
