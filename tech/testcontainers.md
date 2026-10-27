# Testcontainers 1.21.4

## What it is

Testcontainers is a Java library that starts **Docker containers from inside your tests**. A test asks for "a PostgreSQL 18 server"; Testcontainers starts a fresh container, waits until it is ready, hands the test its address, user and password, and removes the container afterwards. The test then runs against the **real** database, not a pretend one. (Docker itself: `docker.md`.)

## Why this repository uses it

The lesson says both storage adapters must pass **the identical contract test class**, and the JDBC one must run "against PostgreSQL in Testcontainers". An in-memory fake could never prove that the conditional `UPDATE` is atomic in a real database. Testcontainers makes "a real PostgreSQL" a dependency of the test with no manual setup.

## Where it appears

| Path | What it does with Testcontainers |
| --- | --- |
| `pom.xml` | `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter` 1.21.4, scope `test`; pins `commons-compress` 1.28.0. |
| `src/test/java/io/plainround/seatholds/support/PostgresFixture.java` | Starts one PostgreSQL container for the whole test run. |
| `src/test/java/io/plainround/seatholds/infrastructure/jdbc/JdbcSeatStoreIT.java` | The contract test against it. |
| `src/test/java/io/plainround/seatholds/infrastructure/jdbc/JdbcHoldRaceIT.java` | The two races against it. |
| `Makefile` (`test-integration`) | Runs them with the `integration` Maven profile (`maven-failsafe.md`). |
| `.github/workflows/ci.yml` (`integration` job) | Runs them on GitHub's Linux machines, which have Docker. |

## How it works underneath

When the test code calls `container.start()`, Testcontainers talks to the **Docker engine** over its local socket (the engine is the background program that runs containers). It pulls the image if missing, creates and starts the container with a **random free port** published on your computer, and then runs a **wait strategy**: for PostgreSQL it reads the container's log until the line `database system is ready to accept connections` has appeared twice (the image starts a temporary server first and then the real one). It returns the connection details: `getJdbcUrl()`, `getUsername()`, `getPassword()`.

A helper container called **Ryuk** normally starts too: its job is to delete leftover containers if the test JVM dies. It is an extra image to pull; on this shared machine pulling it hung, so `make test-integration` sets `TESTCONTAINERS_RYUK_DISABLED=true` by default (CI sets it to `false`).

The tests depend on Docker. They are named `*IT` and run only with `-Pintegration`, so plain `make test` needs no Docker.

## Walkthrough of this repository's use

```java
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
```

- `PostgreSQLContainer<?>` is Testcontainers' ready-made PostgreSQL container. `DockerImageName.parse(System.getenv().getOrDefault("POSTGRES_IMAGE", "postgres:18"))` picks the image: `postgres:18` unless the environment says otherwise.
- `.withStartupTimeout(Duration.ofMinutes(5))`: the default wait is 60 seconds; on a heavily loaded machine the database took several minutes to start, so the limit is raised.
- `EXTERNAL_URL = System.getenv("TEST_DATABASE_URL")`: an escape hatch. If set, the tests use **that** database (for example the compose one from `make up`) and start no container.
- `pool()` is `synchronized` (one thread at a time) and creates the shared connection pool on first use: it opens it from `settings()`, applies `sql/001_schema.sql`, and registers a shutdown hook that closes the pool. Every test class shares one container and pool, because starting PostgreSQL is slow.
- `settings()` returns either the external database or the container's URL, user and password, with a pool of 16 and a three-minute connection timeout.
- `applySchema()` reads **the repository's own** `sql/001_schema.sql` and runs it (`Files.readString(Path.of("sql", "001_schema.sql"))`), so the tests use exactly the DDL a reader runs with `make db`.

```java
package io.plainround.seatholds.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.SeatStoreContract;
import io.plainround.seatholds.support.PostgresFixture;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** The identical contract class the in-memory adapter passes, against PostgreSQL in a container. */
class JdbcSeatStoreIT extends SeatStoreContract {

  @Override
  protected SeatStore newStore() {
    return new JdbcSeatStore(PostgresFixture.pool());
  }

  @Test
  void theDatabaseItselfRefusesAnImpossibleSeat() throws SQLException {
    new JdbcSeatStore(PostgresFixture.pool()).add(new SeatId("shape-check"));

    try (var connection = PostgresFixture.pool().getConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE seats SET state_type = 'HELD' WHERE seat_id = 'shape-check'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("seats_state_shape");
    }
  }

  @Test
  void everyChangeBumpsTheVersionColumn() throws SQLException {
    var store = new JdbcSeatStore(PostgresFixture.pool());
    var seat = new SeatId("version-check");
    store.add(seat);
    store.update(
        seat,
        new io.plainround.seatholds.domain.SeatState.Free(),
        new io.plainround.seatholds.domain.SeatState.Sold(
            new io.plainround.seatholds.domain.CustomerId("ada")));

    try (var connection = PostgresFixture.pool().getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT version FROM seats WHERE seat_id = 'version-check'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isEqualTo(1L);
    }
  }
}
```

`JdbcSeatStoreIT extends SeatStoreContract` supplies only `newStore()`, returning a `JdbcSeatStore` over the shared pool. It inherits every contract test, and adds two database-only tests: that the database itself refuses an impossible row, and that every change increments `version`.

## Try it yourself

1. `make test-integration` runs the database tests (Docker needed; the first run pulls `postgres:18`).
2. `make up` and then `TEST_DATABASE_URL=jdbc:postgresql://localhost:55468/seatholds make test-integration` runs them against the compose database instead.
3. While the tests run, `docker ps` in another terminal shows a `postgres:18` container with a random port (and, if Ryuk is enabled, a `testcontainers/ryuk` one). It disappears when the run ends.
4. `./mvnw -B -Pintegration verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=JdbcSeatStoreIT -Djacoco.skip=true` runs only the contract class.
5. `docker ps -a | grep postgres` after the run shows the container was removed.

## Mistakes people make

- **Using an in-memory database** (such as H2) for tests of database-specific SQL. `IS NOT DISTINCT FROM`, `ON CONFLICT` and partial indexes are PostgreSQL features; only the real thing proves them.
- **A new container per test class.** Slow. Here one is shared.
- **A 60-second start-up limit** on a slow machine. Raise it, as the fixture does.
- **No clean-up.** Without Ryuk a crashed run can leave containers behind; run `docker ps -a` if a test JVM died.
- **Running Docker-dependent tests in the default build,** which breaks laptops without Docker. These are `*IT` tests behind a profile.

## Check yourself

1. *What does Testcontainers start, and when?* A Docker container with the real service, from inside the test, before it runs.
2. *Why share one container across test classes?* Starting PostgreSQL is slow.
3. *How does it know PostgreSQL is ready?* A wait strategy that watches the container's log for the "ready to accept connections" line.
4. *What is Ryuk?* A helper container that removes leftover containers if the test JVM dies.
5. *What does `TEST_DATABASE_URL` do?* Makes the tests use an already-running database instead of starting a container.

## Further reading

- Testcontainers for Java 1.21: <https://java.testcontainers.org/> (PostgreSQL module: <https://java.testcontainers.org/modules/databases/postgres/>)
