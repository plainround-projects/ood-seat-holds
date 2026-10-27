# PostgreSQL 18 (the database server) and `psql`

## What it is

PostgreSQL ("Postgres") is a **relational database server**: a program that runs in the background, stores tables of data safely on disk and answers SQL (`sql.md`) over the network. It keeps its promises even if the computer crashes (the **ACID** properties: each change is atomic, consistent, isolated and durable). `psql` is Postgres's own command-line client: you type SQL, it prints results. This repository uses **PostgreSQL 18**, run in a Docker container.

## Why this repository uses it

The lesson asks for "a JDBC adapter for a real database" and says the guarantee "sold at most once" must hold when two servers race. A real database is where that guarantee is proved: one conditional `UPDATE` is atomic inside Postgres, however many programs send it at once.

## Where it appears

| Path | What it does with PostgreSQL |
| --- | --- |
| `docker-compose.yml` | Runs the server in a container (`docker-compose.md`). |
| `sql/` | The schema, seed rows and queries (`sql.md`). |
| `Makefile` (`up`, `db`, `queries`, `psql`) | Start it, load the files, open a prompt. |
| `src/main/java/io/plainround/seatholds/infrastructure/jdbc/` | The Java adapter that talks to it (`jdbc.md`). |
| `src/test/java/io/plainround/seatholds/support/PostgresFixture.java` | Starts a throwaway server for the tests (`testcontainers.md`). |

## How it works underneath

- **Server process.** `postgres` is one main process (the **postmaster**) that listens on a TCP port (here 5432 inside the container, published on **55468** on your computer). Each client connection gets its own **backend** process. A password check (authentication) happens at connect time.
- **Data on disk.** Tables live in files in a data directory. In this repository's container the data is **not** kept on a volume: `make down` (`docker compose down -v`) deletes it.
- **Concurrency.** Many connections can write at once. Postgres uses **MVCC** (multi-version concurrency control): readers do not block writers. A single `UPDATE` locks only the rows it changes for the instant it runs. Two `UPDATE`s of the same row run one after the other; the second re-checks its `WHERE` against the row the first left behind. That re-check is why the conditional `UPDATE` works as compare-and-set: the loser's `WHERE` no longer matches and it changes 0 rows.
- **Start-up.** The container's first start runs the image's init: it creates the database and user from environment variables, using a **temporary server** that listens only on a local socket, then restarts as the real server. This is why the health check must test the TCP port (below).
- **Memory limits.** On a very busy host Postgres backends can be killed by the operating system; the server then restarts in **recovery mode** and refuses connections for a moment with `the database system is in recovery mode`. (This happened during the development of this repository on an overloaded machine, not because of a code problem.)

## Walkthrough of this repository's use

```yaml
# PostgreSQL for the JDBC adapter. Data lives only as long as the container: `make down` deletes it.
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_USER: seatholds
      POSTGRES_DB: seatholds
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-seatholds-local-only}
    ports:
      - "127.0.0.1:${POSTGRES_PORT:-55468}:5432"
    healthcheck:
      # TCP, not the socket: during first start the image runs a temporary server that listens only on
      # the socket, so a socket check can pass before the database exists.
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U seatholds -d seatholds"]
      interval: 2s
      timeout: 3s
      retries: 30
```

(`docker-compose.md` explains each key.) The settings that matter to Postgres: the image tag `postgres:18` (server version 18), the variables `POSTGRES_USER`, `POSTGRES_DB` and `POSTGRES_PASSWORD` that the image reads on first start to create the database `seatholds` and the user `seatholds`, and the health check `pg_isready -h 127.0.0.1 -U seatholds -d seatholds`. `pg_isready` asks the server "are you accepting connections?". The `-h 127.0.0.1` makes it use **TCP**: the temporary init server only listens on a socket, so a socket check can say "ready" before the database exists. A real bug of this repository's first version was exactly that: `make up` reported healthy and then `psql` said `database "seatholds" does not exist`.

The `Makefile` runs `psql` inside the container:

```make
PSQL := docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds
```
```make
db: ## apply the numbered SQL scripts in order (DDL, then the seed rows)
	$(PSQL) < sql/001_schema.sql
	$(PSQL) < sql/002_seed.sql
```

- `docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds`: run `psql` in the `db` container, connect as user `seatholds` to database `seatholds`. `-v ON_ERROR_STOP=1` is a `psql` variable that makes it stop at the first error (otherwise it carries on and exits 0). `-T` turns off the terminal so the file can be piped in.
- `< sql/001_schema.sql` sends the file as the commands to run.

What the **adapter** relies on from Postgres:

- A single `UPDATE` is atomic and the `WHERE` is evaluated on the current row.
- `ON CONFLICT DO NOTHING` (Postgres syntax) makes the seat insert idempotent.
- `IS NOT DISTINCT FROM` and the `::` cast syntax are Postgres features.
- `timestamptz` stores a moment in UTC with microsecond precision, which is why `Held` truncates to milliseconds.

## Try it yourself

1. `make up` starts it; `docker compose ps` shows the service as `healthy`.
2. `make psql`, then `\dt` lists tables (`seats`), `\d seats` describes it, `\q` quits.
3. `docker compose exec db psql -U seatholds seatholds -c "SELECT version();"` prints `PostgreSQL 18.x ...`.
4. `docker compose logs db | tail` shows the server's own log, including `database system is ready to accept connections`.
5. `make down` stops it and deletes the data; `make up` creates a fresh one.

## Mistakes people make

- **A health check that passes too early** (the socket-only temporary server). Check the TCP port.
- **Using the real superuser password** in a throwaway container file. `.env.example` uses a placeholder for a local container only.
- **Assuming data survives `docker compose down -v`.** It does not; that is deliberate here.
- **Too many connections** (default maximum 100). A pool limits them (`hikaricp.md`).
- **Forgetting that a crashed backend restarts the server** and drops all connections for a moment.

## Check yourself

1. *What is `psql`?* PostgreSQL's command-line client.
2. *Why does `pg_isready -h 127.0.0.1` matter?* It checks over TCP; the temporary init server listens only on a socket, so a socket check can pass before the database exists.
3. *Why is the conditional `UPDATE` safe with two writers?* Postgres runs the two `UPDATE`s one after the other and re-checks the `WHERE` on the row the first left behind, so the second changes nothing.
4. *What does `make down` do to the data?* Deletes it (`docker compose down -v`).
5. *Which environment variables create the database and user on first start?* `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.

## Further reading

- PostgreSQL 18 documentation: <https://www.postgresql.org/docs/18/index.html> (`psql`: <https://www.postgresql.org/docs/18/app-psql.html>, `pg_isready`: <https://www.postgresql.org/docs/18/app-pg-isready.html>)
- Official Docker image (variables and init): <https://hub.docker.com/_/postgres>
