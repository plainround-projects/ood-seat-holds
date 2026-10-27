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

### `JdbcSeatStore.java`, line by line

```java
  1  package io.plainround.seatholds.infrastructure.jdbc;
  2  
  3  import com.zaxxer.hikari.HikariDataSource;
  4  import io.plainround.seatholds.domain.CustomerId;
  5  import io.plainround.seatholds.domain.SeatId;
  6  import io.plainround.seatholds.domain.SeatState;
  7  import io.plainround.seatholds.domain.SeatState.Free;
  8  import io.plainround.seatholds.domain.SeatState.Held;
  9  import io.plainround.seatholds.domain.SeatState.Sold;
 10  import io.plainround.seatholds.domain.SeatStore;
 11  import io.plainround.seatholds.domain.SeatStoreException;
 12  import io.plainround.seatholds.domain.UnknownSeatException;
 13  import java.sql.Connection;
 14  import java.sql.PreparedStatement;
 15  import java.sql.ResultSet;
 16  import java.sql.SQLException;
 17  import java.sql.Types;
 18  import java.time.Instant;
 19  import java.time.OffsetDateTime;
 20  import java.time.ZoneOffset;
 21  import java.util.ArrayList;
 22  import java.util.List;
 23  import javax.sql.DataSource;
 24  
 25  /**
 26   * Stores seats in PostgreSQL ({@code sql/001_schema.sql}). {@link #update} is one conditional
 27   * {@code UPDATE}: the {@code WHERE} clause matches the state the caller read, so a write based on
 28   * stale data changes zero rows and returns false, however many servers race. The port speaks in
 29   * states, not versions, so the clause compares the state columns; the {@code version} column is
 30   * bumped on every change for auditing.
 31   */
 32  public final class JdbcSeatStore implements SeatStore, AutoCloseable {
 33  
 34    private static final int QUERY_TIMEOUT_SECONDS = 10;
 35  
 36    private static final String SELECT_STATE =
 37        "SELECT state_type, holder, expires_at FROM seats WHERE seat_id = ?";
 38    private static final String COMPARE_AND_SET =
 39        """
 40        UPDATE seats
 41           SET state_type = ?, holder = ?::text, expires_at = ?::timestamptz, version = version + 1
 42         WHERE seat_id = ?
 43           AND state_type = ?
 44           AND holder IS NOT DISTINCT FROM ?::text
 45           AND expires_at IS NOT DISTINCT FROM ?::timestamptz
 46        """;
 47    private static final String INSERT_FREE =
 48        "INSERT INTO seats (seat_id, state_type) VALUES (?, 'FREE') ON CONFLICT (seat_id) DO NOTHING";
 49    private static final String LIST_SEATS =
 50        "SELECT seat_id FROM seats ORDER BY seat_id COLLATE \"C\"";
 51  
 52    private final DataSource dataSource;
 53    private final AutoCloseable pool;
 54  
 55    /**
 56     * Creates the store over a data source the caller owns and closes.
 57     *
 58     * @param dataSource the connection pool
 59     */
 60    public JdbcSeatStore(DataSource dataSource) {
 61      this.dataSource = dataSource;
 62      this.pool = () -> {};
 63    }
 64  
 65    private JdbcSeatStore(HikariDataSource dataSource) {
 66      this.dataSource = dataSource;
 67      this.pool = dataSource;
 68    }
 69  
 70    /**
 71     * Opens a connection pool and a store over it; {@link #close} closes the pool.
 72     *
 73     * @param settings where the database is
 74     * @return the store
 75     */
 76    public static JdbcSeatStore open(DatabaseSettings settings) {
 77      return new JdbcSeatStore(PostgresDataSource.open(settings));
 78    }
 79  
 80    @Override
 81    public void close() {
 82      try {
 83        pool.close();
 84      } catch (Exception failure) {
 85        throw new SeatStoreException("closing the connection pool", failure);
 86      }
 87    }
 88  
 89    @Override
 90    public SeatState get(SeatId seat) {
 91      try (Connection connection = dataSource.getConnection();
 92          PreparedStatement statement = prepare(connection, SELECT_STATE)) {
 93        statement.setString(1, seat.value());
 94        try (ResultSet row = statement.executeQuery()) {
 95          if (!row.next()) {
 96            throw new UnknownSeatException(seat);
 97          }
 98          return decode(row);
 99        }
100      } catch (SQLException failure) {
101        throw new SeatStoreException("reading seat " + seat, failure);
102      }
103    }
104  
105    @Override
106    public boolean update(SeatId seat, SeatState expected, SeatState next) {
107      try (Connection connection = dataSource.getConnection();
108          PreparedStatement statement = prepare(connection, COMPARE_AND_SET)) {
109        Columns after = Columns.of(next);
110        Columns before = Columns.of(expected);
111        statement.setString(1, after.stateType());
112        setText(statement, 2, after.holder());
113        setTimestamp(statement, 3, after.expiresAt());
114        statement.setString(4, seat.value());
115        statement.setString(5, before.stateType());
116        setText(statement, 6, before.holder());
117        setTimestamp(statement, 7, before.expiresAt());
118        if (statement.executeUpdate() == 1) {
119          return true;
120        }
121        requireKnown(connection, seat);
122        return false;
123      } catch (SQLException failure) {
124        throw new SeatStoreException("updating seat " + seat, failure);
125      }
126    }
127  
128    @Override
129    public boolean add(SeatId seat) {
130      try (Connection connection = dataSource.getConnection();
131          PreparedStatement statement = prepare(connection, INSERT_FREE)) {
132        statement.setString(1, seat.value());
133        return statement.executeUpdate() == 1;
134      } catch (SQLException failure) {
135        throw new SeatStoreException("adding seat " + seat, failure);
136      }
137    }
138  
139    @Override
140    public List<SeatId> seats() {
141      try (Connection connection = dataSource.getConnection();
142          PreparedStatement statement = prepare(connection, LIST_SEATS);
143          ResultSet rows = statement.executeQuery()) {
144        List<SeatId> seats = new ArrayList<>();
145        while (rows.next()) {
146          seats.add(new SeatId(rows.getString(1)));
147        }
148        return List.copyOf(seats);
149      } catch (SQLException failure) {
150        throw new SeatStoreException("listing seats", failure);
151      }
152    }
153  
154    private void requireKnown(Connection connection, SeatId seat) throws SQLException {
155      try (PreparedStatement statement = prepare(connection, SELECT_STATE)) {
156        statement.setString(1, seat.value());
157        try (ResultSet row = statement.executeQuery()) {
158          if (!row.next()) {
159            throw new UnknownSeatException(seat);
160          }
161        }
162      }
163    }
164  
165    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
166      PreparedStatement statement = connection.prepareStatement(sql);
167      statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
168      return statement;
169    }
170  
171    private static void setText(PreparedStatement statement, int index, String value)
172        throws SQLException {
173      if (value == null) {
174        statement.setNull(index, Types.VARCHAR);
175      } else {
176        statement.setString(index, value);
177      }
178    }
179  
180    private static void setTimestamp(PreparedStatement statement, int index, Instant value)
181        throws SQLException {
182      if (value == null) {
183        statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
184      } else {
185        statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
186      }
187    }
188  
189    private static SeatState decode(ResultSet row) throws SQLException {
190      String type = row.getString("state_type");
191      String holder = row.getString("holder");
192      return switch (type) {
193        case "FREE" -> new Free();
194        case "HELD" ->
195            new Held(
196                new CustomerId(holder),
197                row.getObject("expires_at", OffsetDateTime.class).toInstant());
198        case "SOLD" -> new Sold(new CustomerId(holder));
199        default -> throw new SeatStoreException("unknown state type in the database: " + type, null);
200      };
201    }
202  
203    /** A state flattened into the table's columns; the database's CHECK constraint mirrors it. */
204    private record Columns(String stateType, String holder, Instant expiresAt) {
205  
206      static Columns of(SeatState state) {
207        return switch (state) {
208          case Free free -> new Columns("FREE", null, null);
209          case Held held -> new Columns("HELD", held.customer().value(), held.expiresAt());
210          case Sold sold -> new Columns("SOLD", sold.customer().value(), null);
211        };
212      }
213    }
214  }
```

| Lines | What they do |
| --- | --- |
| 3-23 | Imports. `java.sql.*` are the JDBC types: `Connection` (an open link to the database), `PreparedStatement` (a SQL command with blanks to fill), `ResultSet` (the rows a query returned), `SQLException` (a database error), `Types` (numeric codes for SQL types). `javax.sql.DataSource` is "something that hands out connections". `OffsetDateTime` and `ZoneOffset` are Java's date-time types with a time-zone offset. The `HikariDataSource` import is the one place the pool type appears (`hikaricp.md`). |
| 32 | `public final class JdbcSeatStore implements SeatStore, AutoCloseable`: it promises the four port methods **and** a `close()`. |
| 34 | `QUERY_TIMEOUT_SECONDS = 10`: the longest any statement may run. |
| 36-37 | `SELECT_STATE`: read one seat's columns by id. The `?` is a **placeholder** the program fills later. |
| 38-46 | `COMPARE_AND_SET`, a **text block** (`"""`, a multi-line string) holding the `UPDATE`. `SET state_type = ?, holder = ?::text, expires_at = ?::timestamptz, version = version + 1` writes the new state and adds one to the audit counter. `WHERE seat_id = ? AND state_type = ? AND holder IS NOT DISTINCT FROM ?::text AND expires_at IS NOT DISTINCT FROM ?::timestamptz` matches the row **only if it is still in the state the caller read**. `IS NOT DISTINCT FROM` is a null-safe equality (in SQL `NULL = NULL` is not true; `NULL IS NOT DISTINCT FROM NULL` is). `?::text` and `?::timestamptz` are **casts** that tell PostgreSQL the type of a parameter even when its value is `NULL`. |
| 47-48 | `INSERT_FREE`: add a free seat; `ON CONFLICT (seat_id) DO NOTHING` makes inserting an existing seat a harmless no-op. |
| 49-50 | `LIST_SEATS`: all seat ids ordered by plain byte order (`COLLATE "C"`), like Java's string order whatever the database's language setting. The `\"` in the Java string is an escaped quote. |
| 52-53 | Two `private final` fields: where connections come from, and what to close at the end. |
| 60-63 | The public constructor wraps a `DataSource` the **caller owns** (tests use it): `pool = () -> {}` is a lambda that does nothing, because this store must not close someone else's pool. |
| 65-68 | A private constructor for a pool this class creates: it keeps the pool both as the source of connections and as the thing to close. |
| 76-78 | `static JdbcSeatStore open(DatabaseSettings settings)`: a **factory method** that opens a pool and wraps it. This is why the rest of the program never names Hikari. |
| 80-87 | `close()` closes the pool; `AutoCloseable.close` is allowed to throw any `Exception`, so the `catch` wraps it in `SeatStoreException`. |
| 89-103 | `get`. Lines 91-92: **try-with-resources** opens a connection (from the pool) and a prepared statement; both are closed automatically in reverse order, even if an exception happens; closing a pooled connection returns it to the pool. Line 93 fills placeholder 1 with the seat id (values travel separately from the SQL, which is what stops SQL injection). Line 94 runs the query. `row.next()` moves to the first row and is `false` when there is none (95-97): unknown seat. Line 98 turns the row into a `SeatState`. `SQLException` is a *checked* exception, so lines 100-102 catch it and rethrow a `SeatStoreException` carrying the original as its cause. |
| 105-126 | `update`, the compare-and-set. Lines 109-110 flatten the new and expected states into columns (`Columns.of`). Lines 111-113 fill the **new** values (placeholders 1 to 3), 114 the seat, 115-117 the **expected** values (4 to 7). Line 118: `executeUpdate()` returns how many rows changed; `== 1` means exactly one row matched and changed: the write **won**, return `true`. Otherwise no row matched: line 121 checks whether the seat exists at all (an unknown seat throws), and line 122 returns `false`: someone changed the seat first. There is **no retry** here: if the connection failed after the statement ran, the write might have committed, and a retry could report `false` for a write that happened. |
| 128-137 | `add`: `executeUpdate() == 1` is true when the row was inserted, false when it already existed (the `ON CONFLICT` made it do nothing). |
| 139-152 | `seats()`: three resources opened together, a connection, a statement and the result set. `while (rows.next())` visits each row; `rows.getString(1)` reads the first column; each is wrapped in a `SeatId`. `List.copyOf` returns an unchangeable list. |
| 154-163 | `requireKnown`: runs the single-seat `SELECT` and throws `UnknownSeatException` if there is no row. It reuses the connection of the failed update. |
| 165-169 | `prepare`: creates the prepared statement and sets its query timeout, so every statement has the 10-second limit. |
| 171-178 | `setText`: a `null` text is sent as a SQL `NULL` of type `VARCHAR` (`setNull`), otherwise as text. |
| 180-187 | `setTimestamp`: a `null` instant becomes a `NULL` timestamp-with-time-zone; otherwise the `Instant` is converted to an `OffsetDateTime` in UTC (`OffsetDateTime.ofInstant(value, ZoneOffset.UTC)`) and sent with `setObject`. |
| 189-201 | `decode`: reads the `state_type` and `holder` columns by name, then a `switch` expression builds the state: `FREE` gives `Free`; `HELD` gives `Held` with the holder and `row.getObject("expires_at", OffsetDateTime.class).toInstant()`; `SOLD` gives `Sold`; anything else is an error (`null` is the missing cause). |
| 204-213 | `private record Columns(String stateType, String holder, Instant expiresAt)`: the flat form of a state. Its static method `of` has a `switch` over the sealed `SeatState` with **no `default`**: the compiler proves all three states are handled. |

### `DatabaseSettings.java`: validated connection settings

```java
 1  package io.plainround.seatholds.infrastructure.jdbc;
 2  
 3  import java.time.Duration;
 4  
 5  /**
 6   * Where the database is and how many connections to open. The password comes from the environment
 7   * and is never printed: {@link #toString} leaves it out.
 8   *
 9   * @param url the JDBC URL, such as {@code jdbc:postgresql://localhost:55468/seatholds}
10   * @param user the database user
11   * @param password the database password
12   * @param poolSize the most connections to open, at least one
13   * @param connectionTimeout how long a caller waits for a free connection before the call fails
14   */
15  public record DatabaseSettings(
16      String url, String user, String password, int poolSize, Duration connectionTimeout) {
17  
18    /**
19     * Validates the settings.
20     *
21     * @throws IllegalArgumentException if a part is missing or the pool is empty
22     */
23    public DatabaseSettings {
24      if (url == null || !url.startsWith("jdbc:postgresql:")) {
25        throw new IllegalArgumentException("DATABASE_URL must be a jdbc:postgresql: URL");
26      }
27      if (user == null || user.isBlank() || password == null) {
28        throw new IllegalArgumentException("DATABASE_USER and DATABASE_PASSWORD are required");
29      }
30      if (poolSize < 1) {
31        throw new IllegalArgumentException("POOL_SIZE must be at least 1, was " + poolSize);
32      }
33      if (connectionTimeout == null || connectionTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
34        throw new IllegalArgumentException("CONNECTION_TIMEOUT must be at least PT1S");
35      }
36    }
37  
38    @Override
39    public String toString() {
40      return "DatabaseSettings[url="
41          + url
42          + ", user="
43          + user
44          + ", poolSize="
45          + poolSize
46          + ", connectionTimeout="
47          + connectionTimeout
48          + "]";
49    }
50  }
```

| Lines | What they do |
| --- | --- |
| 3 | Imports `Duration`. |
| 15-16 | A record of the URL, user, password, pool size and connection timeout. |
| 23-36 | The compact constructor validates. Lines 24-26: the URL must start with `jdbc:postgresql:` (JDBC URLs name their database kind first). Lines 27-29: a user and a password are required (`password == null` is refused; an empty password is allowed for a local database). Lines 30-32: the pool needs at least one connection. Lines 33-35: the timeout must be at least one second. |
| 38-49 | `toString` is rewritten to **leave the password out**: it lists the URL, user, pool size and timeout only. Printing the settings can never leak the password. |

### `PostgresDataSource.java`: the pool

```java
 1  package io.plainround.seatholds.infrastructure.jdbc;
 2  
 3  import com.zaxxer.hikari.HikariConfig;
 4  import com.zaxxer.hikari.HikariDataSource;
 5  
 6  /** Builds the connection pool. The pool is the concurrency limit: a thousand callers share it. */
 7  public final class PostgresDataSource {
 8  
 9    private static final long STARTUP_RETRY_MILLIS = 30_000;
10  
11    private PostgresDataSource() {}
12  
13    /**
14     * Opens a pool. If the database is still starting, the pool keeps trying for 30 seconds before
15     * giving up, and every request for a connection waits at most the configured connection timeout,
16     * then fails with an exception rather than queueing forever.
17     *
18     * @param settings where the database is
19     * @return a pool the caller must close
20     */
21    public static HikariDataSource open(DatabaseSettings settings) {
22      HikariConfig config = new HikariConfig();
23      config.setJdbcUrl(settings.url());
24      config.setUsername(settings.user());
25      config.setPassword(settings.password());
26      config.setMaximumPoolSize(settings.poolSize());
27      config.setConnectionTimeout(settings.connectionTimeout().toMillis());
28      config.setInitializationFailTimeout(STARTUP_RETRY_MILLIS);
29      config.setPoolName("seat-holds");
30      return new HikariDataSource(config);
31    }
32  }
```

Explained line by line in `hikaricp.md`.

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
