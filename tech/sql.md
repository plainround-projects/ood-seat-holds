# SQL (`sql/*.sql` and the statements in `JdbcSeatStore`)

## What it is

SQL (Structured Query Language) is the language you use to ask a relational database to store and fetch data. A relational database keeps data in **tables**: a table has named **columns** (the fields) and **rows** (the records). You write SQL as text. The statements fall into groups: **DDL** (data definition language: `CREATE TABLE`, which defines structure), **DML** (data manipulation language: `INSERT`, `UPDATE`, `DELETE`, `SELECT`, which change or read rows) and queries (`SELECT`). This repository uses PostgreSQL's dialect (`postgresql.md`).

## Why this repository uses it

The template says: "add the sql scripts as well in the repo, ddl, dml etc." The numbered files run in order with one command (`make db`): the schema (`001`), the seed rows (`002`) and the queries the lesson runs (`003`). The same schema file is run by the integration tests, so the tests and a reader use identical DDL.

## Where it appears

| Path | What it does with SQL |
| --- | --- |
| `sql/001_schema.sql` | DDL: the `seats` table, its constraints, a comment on each column, an index. |
| `sql/002_seed.sql` | DML: ten free seats `s-1` to `s-10`. |
| `sql/003_queries.sql` | Queries: seats per state, who holds what, expired holds, a consistency check, most-changed seats. |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/JdbcSeatStore.java` | Five SQL statements the program runs (`jdbc.md`). |
| `Makefile` (`db`, `queries`) | Feed the files to `psql` (`postgresql.md`). |
| `src/test/java/io/plainround/seatholds/support/PostgresFixture.java` | Runs `sql/001_schema.sql` before the integration tests. |

## How it works underneath

You send a statement to the database server. The server **parses** it, makes a **plan** for the fastest way to do it, **executes** it and returns a result: a set of rows, or a count of changed rows. SQL says *what* you want, not how to get it.

A **primary key** is a column (or set) whose value is unique per row, so a row can be found by it. A **constraint** is a rule the database enforces on every change: `NOT NULL`, `CHECK (condition)`, primary key. A change that breaks a constraint is rejected with an error. An **index** is an extra structure that makes finding rows by a column fast, like the index of a book. A **transaction** groups statements so they succeed or fail together; each single statement is atomic by itself.

`NULL` means "no value / unknown". It is not equal to anything, not even to another `NULL`: `NULL = NULL` is not true. You test with `IS NULL` or the null-safe `IS NOT DISTINCT FROM`.

## Walkthrough of this repository's use

### `sql/001_schema.sql` (DDL)

```sql
-- 001_schema.sql: DDL for the seat store (PostgreSQL 14 or newer; developed on 18).
-- Run order: 001_schema.sql, 002_seed.sql. `make db` applies both; the integration tests apply this file.

CREATE TABLE IF NOT EXISTS seats (
    seat_id    text        PRIMARY KEY,
    state_type text        NOT NULL,
    holder     text,
    expires_at timestamptz,
    version    bigint      NOT NULL DEFAULT 0,

    CONSTRAINT seats_state_type_known
        CHECK (state_type IN ('FREE', 'HELD', 'SOLD')),

    -- The same states the Java sealed type allows, made unrepresentable here too:
    -- a free seat has no holder and no expiry, a held seat has both, a sold seat has a holder only.
    CONSTRAINT seats_state_shape CHECK (
           (state_type = 'FREE' AND holder IS NULL     AND expires_at IS NULL)
        OR (state_type = 'HELD' AND holder IS NOT NULL AND expires_at IS NOT NULL)
        OR (state_type = 'SOLD' AND holder IS NOT NULL AND expires_at IS NULL)
    )
);

COMMENT ON TABLE  seats            IS 'One row per seat: its state machine position (free, held, sold).';
COMMENT ON COLUMN seats.state_type IS 'FREE, HELD or SOLD; the Java SeatState variant.';
COMMENT ON COLUMN seats.holder     IS 'Customer id of the holder (HELD) or the buyer (SOLD).';
COMMENT ON COLUMN seats.expires_at IS 'Last instant a hold can be confirmed; only for HELD.';
COMMENT ON COLUMN seats.version    IS 'Bumped on every change; for auditing. The compare-and-set matches the state columns.';

-- The sweeper looks for expired holds; this partial index keeps that scan to the held seats.
CREATE INDEX IF NOT EXISTS seats_held_by_expiry ON seats (expires_at) WHERE state_type = 'HELD';
```

- Lines starting `--` are comments.
- `CREATE TABLE IF NOT EXISTS seats (...)` creates the table unless it exists (so the script can be run again).
- Columns: `seat_id text PRIMARY KEY` (the id, unique); `state_type text NOT NULL` (`FREE`, `HELD` or `SOLD`); `holder text` and `expires_at timestamptz` (both may be `NULL`; `timestamptz` is a moment in time with time-zone handling); `version bigint NOT NULL DEFAULT 0` (a whole number that starts at 0; the program adds 1 on every change).
- `CONSTRAINT seats_state_type_known CHECK (state_type IN ('FREE', 'HELD', 'SOLD'))`: only those three words are allowed.
- `CONSTRAINT seats_state_shape CHECK (...)`: the Java sealed type, written as a database rule. A `FREE` seat has no holder and no expiry; a `HELD` seat has both; a `SOLD` seat has a holder and no expiry. `OR` joins the three allowed shapes. A hand-typed `UPDATE` that breaks the shape is rejected, so the table cannot hold a fourth state.
- `COMMENT ON TABLE/COLUMN ... IS '...'` stores documentation inside the database (visible with `\d+ seats` in `psql`).
- `CREATE INDEX IF NOT EXISTS seats_held_by_expiry ON seats (expires_at) WHERE state_type = 'HELD'` is a **partial index**: it indexes only held seats, because the sweeper looks for expired *holds*. It keeps the index small.

### `sql/002_seed.sql` (DML)

```sql
-- 002_seed.sql: DML for the lesson's example: one event with ten seats, all free.
-- Safe to run again: existing seats are left as they are.

INSERT INTO seats (seat_id, state_type)
SELECT 's-' || n, 'FREE'
FROM generate_series(1, 10) AS n
ON CONFLICT (seat_id) DO NOTHING;
```

`INSERT INTO seats (seat_id, state_type) SELECT 's-' || n, 'FREE' FROM generate_series(1, 10) AS n` inserts one row per number 1 to 10: `generate_series` makes the numbers, `||` joins text (`'s-' || 1` is `s-1`). `ON CONFLICT (seat_id) DO NOTHING` skips seats that already exist, so running it twice is safe.

### `sql/003_queries.sql` (queries)

```sql
-- 003_queries.sql: the queries used while running the lesson. Run with `make psql`, or
-- `docker compose exec -T db psql -U seatholds seatholds -f - < sql/003_queries.sql`.

-- How many seats are in each state? After a race over the last ten seats: ten HELD, none FREE.
SELECT state_type, count(*) AS seats FROM seats GROUP BY state_type ORDER BY state_type;

-- Who holds what, and until when? One row per held seat, soonest expiry first.
SELECT seat_id, holder, expires_at FROM seats WHERE state_type = 'HELD' ORDER BY expires_at, seat_id;

-- Which holds has the sweeper not freed yet? The expired ones, found through the partial index.
SELECT seat_id, holder, expires_at FROM seats WHERE state_type = 'HELD' AND expires_at < now() ORDER BY expires_at;

-- Is any seat inconsistent? Should return zero rows; the CHECK constraint makes it impossible.
SELECT seat_id FROM seats
WHERE (state_type = 'FREE' AND (holder IS NOT NULL OR expires_at IS NOT NULL))
   OR (state_type = 'HELD' AND (holder IS NULL OR expires_at IS NULL))
   OR (state_type = 'SOLD' AND (holder IS NULL OR expires_at IS NOT NULL));

-- Which seats changed most often? A seat held, expired and held again has a higher version.
SELECT seat_id, state_type, version FROM seats ORDER BY version DESC, seat_id LIMIT 10;
```

Each query has a comment saying what it shows.

- `SELECT state_type, count(*) AS seats FROM seats GROUP BY state_type ORDER BY state_type;`: one row per state with the number of seats in it (`GROUP BY` groups rows; `count(*)` counts rows in each group; `AS seats` names the column).
- The second query lists held seats with `WHERE state_type = 'HELD'` and `ORDER BY expires_at, seat_id`.
- The third adds `AND expires_at < now()` (`now()` is the current time) to find expired holds the sweeper has not freed.
- The fourth lists rows that break the shape rules; it must return zero rows because the constraint makes them impossible.
- The fifth ends `ORDER BY version DESC, seat_id LIMIT 10`: highest `version` first, at most ten rows.

### The statements inside the program

```java
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

- `SELECT state_type, holder, expires_at FROM seats WHERE seat_id = ?`: read one seat.
- The `UPDATE` with the long `WHERE` is the compare-and-set explained in `jdbc.md`.
- `INSERT ... VALUES (?, 'FREE') ON CONFLICT (seat_id) DO NOTHING`: add a free seat if absent.
- `SELECT seat_id FROM seats ORDER BY seat_id COLLATE "C"`: all seats in byte order.

## Try it yourself

1. `make up` creates the database and runs `001` and `002`.
2. `make queries` runs `003_queries.sql` and prints five result tables.
3. `make psql`, then `\d+ seats` shows the columns, constraints, comments and the index.
4. In `psql`: `INSERT INTO seats (seat_id, state_type, holder) VALUES ('x', 'FREE', 'ada');` fails with `violates check constraint "seats_state_shape"`.
5. In `psql`: `SELECT 's-' || n FROM generate_series(1, 3) AS n;` prints `s-1`, `s-2`, `s-3`.

## Mistakes people make

- **`= NULL`** instead of `IS NULL`. Never true.
- **A constraint only in application code.** If someone edits the table by hand, only a database constraint stops it. Here the Java type and the `CHECK` agree.
- **Scripts that fail on the second run.** `IF NOT EXISTS` and `ON CONFLICT DO NOTHING` make these re-runnable.
- **Building SQL from user text** in Java (injection). The program uses placeholders (`jdbc.md`).
- **Indexing everything.** An index speeds reads but slows writes; this one is partial and exists for the sweeper's lookup.

## Check yourself

1. *What is DDL versus DML?* DDL defines structure (`CREATE TABLE`); DML changes or reads rows (`INSERT`, `UPDATE`, `SELECT`).
2. *What does the `seats_state_shape` constraint guarantee?* A free seat has no holder or expiry, a held seat has both, a sold seat has a holder only.
3. *Why `ON CONFLICT (seat_id) DO NOTHING`?* Re-running the insert must not fail or duplicate seats.
4. *What does `GROUP BY state_type` do?* Groups rows by state so `count(*)` counts each group.
5. *What is a partial index?* An index covering only the rows that match a condition (here, held seats).

## Further reading

- PostgreSQL 18 SQL language reference: <https://www.postgresql.org/docs/18/sql.html> (`CREATE TABLE`: <https://www.postgresql.org/docs/18/sql-createtable.html>, `INSERT ... ON CONFLICT`: <https://www.postgresql.org/docs/18/sql-insert.html>)
