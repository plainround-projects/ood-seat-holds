# HikariCP 7.1.0 (the connection pool)

## What it is

A **connection pool** keeps a small set of open database connections and lends them out. Opening a connection is slow (a network handshake and a login), so a program borrows one, uses it for a moment and gives it back. **HikariCP** is a widely used, fast pool for Java. You configure it, then ask it for a `Connection` like any other: `dataSource.getConnection()`.

## Why this repository uses it

The race has 1,000 virtual threads that each need the database. Opening 1,000 connections would overwhelm PostgreSQL (default maximum: 100). With a pool of 16, at most 16 statements run at once and the other threads wait their turn. The pool is the **concurrency limit**.

## Where it appears

| Path | What it does with HikariCP |
| --- | --- |
| `pom.xml` | Declares `com.zaxxer:HikariCP` 7.1.0 (default scope: it is used by the code). |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/PostgresDataSource.java` | Creates and configures the pool. |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/JdbcSeatStore.java` | Owns the pool in `open(...)` and closes it in `close()`. |
| `src/test/java/io/plainround/seatholds/support/PostgresFixture.java` | Creates a pool for the tests. |

## How it works underneath

At start-up Hikari opens connections up to the **maximum pool size**. `getConnection()` hands out an idle connection, or **waits** if all are in use. If none becomes free within the **connection timeout**, it throws `SQLTransientConnectionException`. Calling `close()` on the borrowed connection does not close it; it returns it to the pool. Hikari writes its own log messages through the SLF4J facade (`slf4j.md`).

`HikariDataSource` is an `AutoCloseable`: closing it closes all the connections.

## Walkthrough of this repository's use

File: `src/main/java/io/plainround/seatholds/infrastructure/jdbc/PostgresDataSource.java`

**Lines 1 to 7**

```java
package io.plainround.seatholds.infrastructure.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Builds the connection pool. The pool is the concurrency limit: a thousand callers share it. */
public final class PostgresDataSource {
```

**Lines 9 to 21**

```java
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
```

**Lines 22 to 32**

```java
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
```

| Lines | What they do |
| --- | --- |
| 3-4 | Imports Hikari's two classes: `HikariConfig` (the settings holder) and `HikariDataSource` (the pool). |
| 7 | `public final class PostgresDataSource`: a class that only holds one static method. |
| 9 | `STARTUP_RETRY_MILLIS = 30_000`: 30 seconds (the underscore in a number is only for reading). |
| 11 | A private constructor: nobody creates one. |
| 21 | `public static HikariDataSource open(DatabaseSettings settings)`: builds and starts a pool from validated settings. |
| 22 | `new HikariConfig()` collects the settings. |
| 23-25 | `setJdbcUrl`, `setUsername`, `setPassword`: where to connect and as whom. |
| 26 | `setMaximumPoolSize(settings.poolSize())`: at most that many connections (default 16, from `POOL_SIZE`). |
| 27 | `setConnectionTimeout(...)`: how long a caller may wait for a free connection (default 30 seconds, from `CONNECTION_TIMEOUT`); after that `getConnection()` throws a clear exception instead of waiting forever. It was 10 seconds at first and was too short for 1,000 waiting threads on a heavily loaded computer, so it became a setting. |
| 28 | `setInitializationFailTimeout(STARTUP_RETRY_MILLIS)`: at start-up keep retrying for 30 seconds if the database is not ready yet, rather than failing at once. This is the "retry on the network call" rule applied where it is safe (opening a pool, not a write). |
| 29 | `setPoolName("seat-holds")`: the name that appears in log lines. |
| 30 | `new HikariDataSource(config)` creates and starts the pool. |

`JdbcSeatStore.open(settings)` calls this and keeps the pool, and `JdbcSeatStore.close()` closes it. The command line's `Application` closes the store when the command ends.

When the pool is too small for the load or the machine is too busy, the error looks like this (seen during development):

```text
SQLTransientConnectionException: seat-holds - Connection is not available, request timed out after 10074ms (total=16, active=16, idle=0, waiting=863)
```

It reads: 16 connections exist, all 16 are active, none idle, 863 threads are waiting.

## Try it yourself

1. `make up`, then `make race RUNS=1 STORE=jdbc` runs one race through the pool.
2. `POOL_SIZE=2 make race RUNS=1 STORE=jdbc` uses a pool of 2: it still gives `wins=10 losses=990`, only slower.
3. `CONNECTION_TIMEOUT=PT1S POOL_SIZE=1 make race RUNS=1 STORE=jdbc` makes waiting threads give up after one second and the command fails with the "request timed out" error (stop with Ctrl-C if it is slow).
4. `make psql` then `SELECT count(*) FROM pg_stat_activity WHERE datname='seatholds';` while a race runs shows about as many connections as the pool size.
5. `grep -n "hikari" pom.xml` shows the pinned version property.

## Mistakes people make

- **A pool as large as the number of threads.** The database, not the application, then has too many connections. Keep it small.
- **Holding a connection while doing slow non-database work,** which starves other threads. Each method here borrows, runs one statement and returns.
- **Not closing the pool** at shutdown, leaving connections open on the server. `JdbcSeatStore.close()` and `Application.close()` handle it.
- **An infinite wait for a connection.** The timeout turns a stuck system into an error with numbers.
- **Treating pool exhaustion as a database problem.** Often the pool is just too small or too slow; the message shows active, idle and waiting counts.

## Check yourself

1. *Why use a pool?* Opening connections is slow and the database allows only a limited number; a pool reuses a few.
2. *What does `close()` on a borrowed connection do?* Returns it to the pool; it does not close the real connection.
3. *What happens when all connections are busy?* `getConnection()` waits, up to the connection timeout, then throws.
4. *What does `setInitializationFailTimeout` change?* The pool keeps retrying at start-up for up to that long if the database is not reachable yet.
5. *What limits how many statements run at the same time here?* The pool size (16 by default).

## Further reading

- HikariCP 7.x README and configuration: <https://github.com/brettwooldridge/HikariCP>
