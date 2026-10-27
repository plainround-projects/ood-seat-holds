# JDBC and the PostgreSQL driver 42.7.14

## What it is

**JDBC** (Java Database Connectivity) is the part of Java's standard library (`java.sql`, `javax.sql`) that lets a Java program talk to a database. It defines the interfaces: `Connection`, `PreparedStatement`, `ResultSet`. A **driver** is a library that implements those interfaces for one database. The **PostgreSQL JDBC driver** (`org.postgresql:postgresql`, version **42.7.14**) speaks PostgreSQL's network protocol. The program is written against the JDBC interfaces; the driver does the real work. You never write the driver's class name: JDBC finds it from the address you connect to, `jdbc:postgresql://host:port/database`.

## Why this repository uses it

The lesson asks for "a JDBC adapter for a real database" that passes the same contract test as the in-memory adapter. JDBC is the standard, framework-free way: no ORM (a library that maps objects to tables), so every SQL statement is visible and the conditional `UPDATE` that makes "sold at most once" true is written out in full.

## Where it appears

| Path | What it does with JDBC |
| --- | --- |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/JdbcSeatStore.java` | The adapter: every SQL statement and how results are read. |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/DatabaseSettings.java` | The validated connection settings. |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/PostgresDataSource.java` | Builds the pool of connections (`hikaricp.md`). |
| `pom.xml` | `org.postgresql:postgresql` 42.7.14, scope `runtime` (the code never names a driver class). |
| `src/test/java/io/plainround/seatholds/support/PostgresFixture.java` | Opens a pool for the integration tests. |

## How it works underneath

1. **Connect.** `DriverManager` or a pool calls the driver with the URL, user and password. The driver opens a **TCP connection** to PostgreSQL (a network channel to host and port), authenticates, and returns a `Connection`. Opening a connection is slow (milliseconds to tens of ms), so programs reuse them through a **pool** (`hikaricp.md`).
2. **Statement.** `connection.prepareStatement(sql)` sends the SQL text with `?` **placeholders**. `statement.setString(1, value)` fills placeholder 1. The values travel **separately** from the SQL, so a value can never be mistaken for SQL code: that is what stops **SQL injection**, the attack where input like `'; DROP TABLE seats; --` becomes part of the command.
3. **Run.** `executeQuery()` runs a `SELECT` and returns a `ResultSet`: a cursor over the rows (`next()` moves to the next row, `getString("holder")` reads a column). `executeUpdate()` runs `INSERT`, `UPDATE` or `DELETE` and returns **how many rows changed**.
4. **Close.** `Connection`, `PreparedStatement` and `ResultSet` all hold resources. **try-with-resources** (`try (Connection c = ...; PreparedStatement s = ...) { ... }`) closes them in reverse order even if an exception happens. Closing a pooled connection gives it back to the pool.
5. **Errors.** Any problem throws `SQLException` (a *checked* exception: the compiler forces you to handle or declare it). The adapter wraps it in the repository's own `SeatStoreException`, so the rest of the program does not depend on `java.sql`.

**Autocommit.** By default each statement is its own **transaction** (an all-or-nothing unit of work) and is committed as soon as it runs. Here each operation is one statement, so that is exactly what is wanted: the conditional `UPDATE` is atomic by itself.

## Walkthrough of this repository's use

### The SQL, as constants

```java
private static final int QUERY_TIMEOUT_SECONDS = 10;

private static final String SELECT_STATE =
    "SELECT state_type, holder, expires_at FROM seats WHERE seat_id = ?";
private static final String COMPARE_AND_SET =
    """
    UPDATE seats
       SET state_type = ?, holder = ?::text, expires_at = ?::timestamptz, version = version + 1
     WHERE seat_id = ?
       AND state_type = ?
       AND holder IS NOT DISTINCT FROM ?::text
       AND expires_at IS NOT DISTINCT FROM ?::timestamptz
    """;
private static final String INSERT_FREE =
    "INSERT INTO seats (seat_id, state_type) VALUES (?, 'FREE') ON CONFLICT (seat_id) DO NOTHING";
private static final String LIST_SEATS =
```

Each statement is a text constant (the `"""` text block holds the multi-line `UPDATE`).

- `SELECT_STATE`: read one seat's columns by id.
- `COMPARE_AND_SET`: the `UPDATE` that matters.
  - `SET state_type = ?, holder = ?::text, expires_at = ?::timestamptz, version = version + 1` writes the **new** state and bumps the audit counter.
  - `WHERE seat_id = ? AND state_type = ? AND holder IS NOT DISTINCT FROM ?::text AND expires_at IS NOT DISTINCT FROM ?::timestamptz` matches only if the row is **still in the state the caller read**. This is the compare-and-set: if another caller changed the row first, the `WHERE` matches nothing and **0 rows change**.
  - `IS NOT DISTINCT FROM` is a null-safe equality: in SQL `NULL = NULL` is not true, but `NULL IS NOT DISTINCT FROM NULL` is. A free seat has a `NULL` holder, so the plain `=` would never match.
  - `?::text` and `?::timestamptz` are **casts**: they tell PostgreSQL the type of a parameter even when its value is `NULL`, which it could not guess otherwise.
- `INSERT_FREE`: add a free seat; `ON CONFLICT (seat_id) DO NOTHING` makes inserting an existing seat a harmless no-op.
- `LIST_SEATS` ends with `ORDER BY seat_id COLLATE "C"`: sort by plain byte order, like Java's `String` order, whatever the database's language setting (a **collation** is a sort-order rule).
- `QUERY_TIMEOUT_SECONDS` (10): the longest any statement may run.

### Reading a seat

```java
public SeatState get(SeatId seat) {
  try (Connection connection = dataSource.getConnection();
      PreparedStatement statement = prepare(connection, SELECT_STATE)) {
    statement.setString(1, seat.value());
    try (ResultSet row = statement.executeQuery()) {
      if (!row.next()) {
        throw new UnknownSeatException(seat);
      }
      return decode(row);
    }
  } catch (SQLException failure) {
    throw new SeatStoreException("reading seat " + seat, failure);
```

Try-with-resources opens a connection and a statement, `setString(1, seat.value())` fills the placeholder, `executeQuery` runs it, and `row.next()` is false when no row matches, which means the seat is unknown (`UnknownSeatException`). `decode(row)` builds the right `SeatState` from the columns. The `catch` wraps `SQLException`.

### The compare-and-set

```java
public boolean update(SeatId seat, SeatState expected, SeatState next) {
  try (Connection connection = dataSource.getConnection();
      PreparedStatement statement = prepare(connection, COMPARE_AND_SET)) {
    Columns after = Columns.of(next);
    Columns before = Columns.of(expected);
    statement.setString(1, after.stateType());
    setText(statement, 2, after.holder());
    setTimestamp(statement, 3, after.expiresAt());
    statement.setString(4, seat.value());
    statement.setString(5, before.stateType());
    setText(statement, 6, before.holder());
    setTimestamp(statement, 7, before.expiresAt());
    if (statement.executeUpdate() == 1) {
      return true;
    }
    requireKnown(connection, seat);
    return false;
  } catch (SQLException failure) {
    throw new SeatStoreException("updating seat " + seat, failure);
```

`Columns.of(state)` flattens a state into three columns. Parameters 1 to 3 are the **new** values, 4 is the seat, 5 to 7 are the **expected** values. `executeUpdate() == 1` means one row matched and changed: the write won (`true`). Otherwise `requireKnown` checks whether the seat exists at all (an unknown seat throws; a known seat means someone else changed it first, so return `false`). No retry here: if the connection fails after the statement ran, the write might have committed; retrying could report `false` for a write that happened, so the exception goes to the caller.

### Nulls and times

```java
private static void setText(PreparedStatement statement, int index, String value)
    throws SQLException {
  if (value == null) {
    statement.setNull(index, Types.VARCHAR);
  } else {
    statement.setString(index, value);
  }
}

private static void setTimestamp(PreparedStatement statement, int index, Instant value)
    throws SQLException {
  if (value == null) {
    statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
  } else {
    statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
```

`setNull(index, Types.VARCHAR)` sends a SQL `NULL` of a stated type. An `Instant` is stored as an `OffsetDateTime` in UTC (the driver maps it to `timestamptz`).

```java
private static SeatState decode(ResultSet row) throws SQLException {
  String type = row.getString("state_type");
  String holder = row.getString("holder");
  return switch (type) {
    case "FREE" -> new Free();
    case "HELD" ->
        new Held(
            new CustomerId(holder),
            row.getObject("expires_at", OffsetDateTime.class).toInstant());
    case "SOLD" -> new Sold(new CustomerId(holder));
    default -> throw new SeatStoreException("unknown state type in the database: " + type, null);
```

`decode` reads `state_type`, `holder` and, for `HELD`, `expires_at` as an `OffsetDateTime`, then `toInstant()`. A `switch` on the text builds `Free`, `Held` or `Sold`; an unknown value is an error.

```java
private record Columns(String stateType, String holder, Instant expiresAt) {

  static Columns of(SeatState state) {
    return switch (state) {
```

`Columns` is a private helper record: the flat form of a state. Its `switch` over the sealed `SeatState` has no `default`.

### Connection settings

```java
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
```

A record that **validates** its values (the URL must start with `jdbc:postgresql:`, the pool size at least one, the timeout at least one second) and whose `toString` **leaves the password out**, so printing the settings can never leak it. The password comes from the environment (`environment-variables.md`).

## Try it yourself

1. `make up` starts PostgreSQL; `make run-jdbc` runs the scenarios against it (Docker needed).
2. `make psql` opens a SQL prompt; run `SELECT seat_id, state_type, holder FROM seats ORDER BY seat_id LIMIT 5;`.
3. In `make psql`: `UPDATE seats SET state_type='HELD' WHERE seat_id='s-1';` fails with `violates check constraint "seats_state_shape"`: the table forbids a held seat with no holder.
4. `STORE=jdbc DATABASE_URL=jdbc:postgresql://localhost:55468/seatholds DATABASE_USER=seatholds DATABASE_PASSWORD=seatholds-local-only java -cp "target/classes:target/lib/*" io.plainround.seatholds.interfaces.cli.Main status` shows the seats as the program sees them.
5. Compare: `SELECT seat_id, state_type, version FROM seats ORDER BY version DESC LIMIT 3;` shows the `version` bump on seats that changed state.

## Mistakes people make

- **Building SQL by joining strings** (`"... WHERE id = '" + id + "'"`): SQL injection. Always use placeholders.
- **Forgetting to close** connections, statements or result sets. Try-with-resources closes them.
- **`= NULL` in SQL.** It is never true; use `IS NULL` or `IS NOT DISTINCT FROM`.
- **Retrying a write blindly** after a connection error. It may have committed.
- **Reading a `timestamp` as a `String`** and parsing by hand. Use `OffsetDateTime`.
- **No query timeout.** A stuck statement blocks a connection forever; here each statement has a 10-second limit.

## Check yourself

1. *What does `executeUpdate()` return?* The number of rows the statement changed.
2. *How does the conditional `UPDATE` implement compare-and-set?* Its `WHERE` clause matches only a row still in the expected state; if another caller changed it first, 0 rows change and the method returns `false`.
3. *Why `IS NOT DISTINCT FROM` instead of `=`?* It treats two NULLs as equal; `NULL = NULL` is never true in SQL.
4. *What stops SQL injection here?* Placeholders: values are sent separately from the SQL text.
5. *Why wrap `SQLException` in `SeatStoreException`?* So the rest of the program does not depend on `java.sql`.

## Further reading

- JDBC (`java.sql`, Java 21): <https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/package-summary.html>
- PostgreSQL JDBC Driver 42.7.x documentation: <https://jdbc.postgresql.org/documentation/>
