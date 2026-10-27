# ood-seat-holds

The seat-hold core of an event ticketing system: a seat is free, held for one customer for ten minutes, sold, or released when the hold expires, and "sold at most once" is guaranteed by the store's compare-and-set, proved by racing a thousand virtual threads for ten seats on two adapters.

| | |
| --- | --- |
| **Lesson** | `/prep/ood/project-seat-holds`, "Design seat holds that survive a race" (`data-structure-explainer/src/content/tracks/ood/108-project-seat-holds.md` in the Plainround app) |
| **Planned hours** | 14 |
| **Level** | Advanced |
| **Architecture** | [ARCHITECTURE.md](ARCHITECTURE.md), decisions in [docs/adr](docs/adr) |
| **Author** | Sumit Srivastava |

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 21 or newer (tested with Temurin 25.0.2; CI runs 21 and 25) | compiling and running; virtual threads |
| GNU Make | 3.81 or newer (the one on macOS works) | every command below |
| Docker with Compose v2 | 24 or newer (tested with 24.0.6) | the PostgreSQL adapter: `make up`, `make test-integration` |
| PostgreSQL image | `postgres:18` (override the tests' image with `POSTGRES_IMAGE`) | pulled by Docker |
| Python 3 | 3.9 or newer, standard library only | `make audit` |
| Maven | not needed: the wrapper `./mvnw` (Maven 3.9.16) is checked in | the build |

No cloud account. The only secret-shaped value is the local database password in `.env.example`, a placeholder for a throwaway container; real settings come from the environment. Libraries are pinned in `pom.xml` (PostgreSQL driver 42.7.14, HikariCP 7.1.0, slf4j 2.0.17 at run time; JUnit 6.0.3, AssertJ 3.27.7, ArchUnit 1.5.1, Testcontainers 1.21.4 in tests). Maven has no lock file; every plugin and dependency version is explicit, one `slf4j-api` is forced for HikariCP and Testcontainers, and `maven-enforcer-plugin` fails the build on an unpinned plugin or a dependency conflict.

## Setup, run, test, stop

```bash
make run                 # the lesson's scenarios on the in-memory store, and the failure they guard against
make up                  # start PostgreSQL in a container and apply sql/001_schema.sql and sql/002_seed.sql
make run-jdbc            # the same scenarios on PostgreSQL
make race RUNS=20        # 20 races of 1,000 customers for 10 seats; add STORE=jdbc for PostgreSQL (after make up)
make queries             # sql/003_queries.sql: seats per state, who holds what, expired holds
make serve               # sweep expired holds every SWEEP_INTERVAL until Ctrl-C, then stop cleanly
make test                # unit, architecture and end-to-end tests with the coverage gate (no Docker needed)
make test-integration    # also the contract and race tests against PostgreSQL in a container (Docker needed)
make lint                # google-java-format check, javac -Xlint:all -Werror, checkstyle
make audit               # known-vulnerability check of the resolved dependencies (needs internet)
make check               # lint + test
make down                # stop PostgreSQL and delete its data
```

`make help` lists the targets. Settings come from the environment, all optional and validated at start-up (a bad value exits with code 2 and names the variable); see [.env.example](.env.example): `STORE` (`memory` or `jdbc`), `HOLD_DURATION`, `SWEEP_INTERVAL`, `SEATS`, `LOG_LEVEL`, and for `jdbc` `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `POOL_SIZE` and `CONNECTION_TIMEOUT`. Copy it to `.env` to change them (the Makefile reads it). Log lines (`ts=... level=... event=...`) go to standard error; results go to standard output.

Two practical notes on the PostgreSQL path. The race puts 1,000 virtual threads on a pool of 16 connections; a caller that cannot get a connection within `CONNECTION_TIMEOUT` (default 30 seconds) fails with a clear `SeatStoreException` instead of queueing forever, so on a very busy machine raise it (`CONNECTION_TIMEOUT=PT5M make run-jdbc`). And `make test-integration` starts a throwaway PostgreSQL with Testcontainers; to reuse the database from `make up` instead (faster, and no image pull), set `TEST_DATABASE_URL=jdbc:postgresql://localhost:55468/seatholds` (user and password default to the values in `.env.example`, or set `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`).

Single operations, to feel the state machine (with `STORE=jdbc` the state persists between commands; in memory every command starts with fresh seats):

```bash
java -cp "target/classes:target/lib/*" io.plainround.seatholds.interfaces.cli.Main hold s-9 ada
java -cp "target/classes:target/lib/*" io.plainround.seatholds.interfaces.cli.Main status
```

What `make run` prints (the wins, losses and Sold/Free split vary a little between runs; the invariants do not):

```text
confirm expired hold -> Reason[message=hold expired]  (never Success)
first update: true  second update (stale expected): false
race result: wins=10 losses=990
race result: wins=10 losses=990
race result: wins=10 losses=990
race result: wins=10 losses=990
race result: wins=10 losses=990
-- a confirm and the expiry sweep meet on one seat, 100 times --
rep 1    seat ended as Free[]
rep 2    seat ended as Free[]
rep 3    seat ended as Free[]
...
rep 100  seat ended as Free[]
sold=16 free=84 inconsistent=0  (every repetition: exactly one of Sold or Free, never both, never a third state)
-- the failure: a plain write instead of compare-and-set --
first update: true  second update (stale expected): true  <- both were told they won seat s-9; it now belongs to Held[customer=bruno, expiresAt=...]
```

The first lines are the lesson's "Expected output". The last block is the failure the lesson teaches: a store whose `update` is a plain write lets both callers win.

## Done when: the lesson's checklist and the proof for each line

| # | Done when (from the lesson) | Command | What proves it |
| --- | --- | --- | --- |
| 1 | The diagram shows which component guarantees "a seat is sold at most once", and it is not the domain object. | Read the [class diagram](ARCHITECTURE.md#class-diagram), the ["Components"](ARCHITECTURE.md#components-and-what-each-owns) table and [ADR 0001](docs/adr/0001-the-guarantee-lives-in-the-store.md) | `SeatStore.update` is the guarantee; `PaymentGateway` is drawn as a named port with no adapter. `ArchitectureTest.theDomainHasNoLocksOrThreadsBecauseTheGuaranteeLivesInTheStore` fails the build if the domain imports `java.util.concurrent`. |
| 2 | A test shows that confirming an expired hold returns a reason, not a sale, using a fixed `Clock`. | `make test`, `make run` | `SeatTransitionsTest.confirmingAnExpiredHoldGivesAReasonNotASale` (a `MutableClock` fixed at one instant) and the first line of `make run`: `Reason[message=hold expired]  (never Success)`. The boundary is tested too: a confirm at the exact expiry succeeds, one millisecond later fails. |
| 3 | The contract test has cases for a stale expected state and for two updates of one seat, and the in-memory store passes them. | `make test` | `SeatStoreContract` (abstract) holds `aStaleExpectedStateIsRejectedAndNothingChanges` and `twoConcurrentUpdatesOfOneSeatExactlyOneSucceeds` (25 rounds), plus unknown seats, value equality and ordering; `InMemorySeatStoreTest extends SeatStoreContract` and passes all 10. |
| 4 | Both adapters pass the identical contract test class, with no test changed. | `make test-integration` (or `TEST_DATABASE_URL=... make test-integration` against `make up`) | `JdbcSeatStoreIT extends SeatStoreContract` against PostgreSQL 18 in a container (Testcontainers, schema from `sql/001_schema.sql`). Same class, same 10 cases, plus two JDBC-only tests (the database refuses an impossible row; every change bumps `version`). |
| 5 | Every run has exactly 10 successful holds and 990 clear rejections, over 20 runs per adapter. | `make test` (in memory), `make test-integration` (PostgreSQL), `make race RUNS=20` | `HoldRaceTest` and `JdbcHoldRaceIT`: 20 runs each of 1,000 virtual threads, every run asserted `wins=10 losses=990`; every seat held by exactly one customer. The harness has each customer try every seat, so threads collide. |
| 6 | Across 100 repetitions of that race, a seat is never both sold and released, and the README explains why. | `make test`, `make test-integration`, `make run` | `SweepConfirmRaceTest` and `JdbcHoldRaceIT`: 100 repetitions each, every one ending `Sold` (the confirm won, the sweep released nothing) or `Free` (the sweep won, the confirm was refused), never both. **Why:** the confirm and the sweeper both read the same `Held` state and decide different, correct transitions; both write through `SeatStore.update(seat, expected, next)` with the same `expected`, so only one can match. The loser's write returns false, it reads again, sees `Sold` or `Free`, and answers with a reason. Neither can overwrite the other because neither writes without comparing. A rendezvous inside the test store makes "both read before either wrote" certain on every repetition rather than merely likely. |

## Stretch goals

| Stretch (from the lesson) | Built? |
| --- | --- |
| Run two instances of the application against one database and repeat the race through HTTP. | No. There is no HTTP layer (the lesson's interface is the command line). Two instances against one database already work: start `make up`, then run `make race STORE=jdbc DATABASE_URL=... ` in two terminals on the same seats' batch; ARCHITECTURE.md ("How to extend it") describes the HTTP step. |
| Add an idempotency key to "confirm", so a retried payment callback never sells twice. | No. It needs the `PaymentGateway` adapter the lesson leaves out; the design is in ARCHITECTURE.md. |
| Replace the sweeper with a hold expiry checked on read, and compare the two designs. | No. The design is in ARCHITECTURE.md ("How to extend it"): `get` treats an expired hold as free and `hold` writes over it through the same compare-and-set, which `HoldRace` can measure. |

## What is simplified for teaching

Only what the lesson scopes out:

- One event with ten seats; no seat map, no groups of seats held together.
- No `PaymentGateway` adapter: a named port on the diagram, with no Java type until something calls it.
- No HTTP: the command line drives the use cases.
- The sweeper is a fixed-delay scheduler in one process, not a distributed job.
- Reasons are plain strings, as in the lesson.

## Project structure

```text
.
├── pom.xml                      build, pinned plugin and library versions, coverage gate, failsafe profile, formatter, checkstyle
├── mvnw, .mvn/                  Maven wrapper (no Maven install needed)
├── Makefile                     run, run-jdbc, race, up, db, test, test-integration, lint, audit, check, down
├── docker-compose.yml           PostgreSQL 18 for the JDBC adapter
├── Dockerfile                   multi-stage build, JRE-only image, non-root user
├── sql/                         001_schema.sql (DDL), 002_seed.sql (DML), 003_queries.sql: run in order with `make db`
├── config/checkstyle.xml        lint rules (Javadoc on the public surface, no TODOs, no unused imports)
├── scripts/audit.py             dependency audit against the OSV database
├── docs/adr/                    decision records
├── tech/                        37 notes, one per technology used here, written for a complete beginner (start at tech/README.md)
├── src/main/java/io/plainround/seatholds/
│   ├── domain/                  SeatState, Outcome, SeatTransitions, the SeatStore and Clock ports: depends on java.* only
│   ├── application/             HoldSeats, ConfirmHold, ExpireHolds, SeatHoldService: depends on domain
│   │   └── simulation/          HoldRace, SweepConfirmRace: the lesson's two races as drivers
│   ├── infrastructure/
│   │   ├── memory/              InMemorySeatStore (and PlainWriteSeatStore, the counter-example)
│   │   ├── jdbc/                JdbcSeatStore, PostgresDataSource, DatabaseSettings: the only code that touches java.sql
│   │   ├── scheduling/          ScheduledSweeper
│   │   └── time/                SystemClock
│   └── interfaces/cli/          Cli, Scenario, AppConfig, Application (composition root), Main: nothing depends on it
└── src/test/java/io/plainround/seatholds/
    ├── domain/, application/, infrastructure/, interfaces/   unit tests, mirroring the packages
    ├── infrastructure/SeatStoreContract.java                 the abstract contract every adapter must pass
    ├── infrastructure/jdbc/                                  *IT classes: PostgreSQL in a container (make test-integration)
    ├── architecture/        ArchUnit: the dependency rule, no concurrency in the domain, SQL only in the JDBC adapter
    ├── e2e/                 the lesson's scenarios through the command
    └── support/             MutableClock, PostgresFixture
```

The dependency rule is enforced by `architecture/ArchitectureTest`; a wrong import fails `make test`. Migrations: the lesson changes the schema in no steps, so `sql/migrations/` does not exist yet; the first schema change would add `sql/migrations/001_...sql` and a line in the Makefile's `db` target.

## Quality gates

`make check` runs the formatter check, the compiler with `-Xlint:all -Werror` (the type check), checkstyle, all unit, architecture and end-to-end tests, and a JaCoCo gate: the `domain`, `application` and `application.simulation` packages must keep at least 95 percent line and 90 percent branch coverage. `make test-integration` adds the PostgreSQL tests. `make audit` queries the OSV database for every resolved dependency. CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs `make check` and `make run` on Java 21 and 25, `make test-integration` once, and `make audit` once.

## Technology notes

The [tech/](tech/README.md) folder teaches every technology this repository uses, from zero, through the repository's own files: 37 notes, one per language, library, tool, service and file format. Each explains what the technology is, why it is here, how it works underneath, and walks through this repository's own code and settings line by line, with commands to try and five questions to check yourself. Start with the ten-minute shortlist in [tech/README.md](tech/README.md).

## Verification

What was run, on 2026-10-27, on macOS with Temurin 25.0.2 (CI also targets Java 21):

- Fresh clone, following this README from the top: `make test` (85 tests, coverage gate met), `make lint` and `make run` pass, and `git status` is clean afterwards.
- `make audit`: 40 dependencies, 0 known advisories (after pinning `commons-compress` 1.28.0).
- `make up`, `make queries` and `make run-jdbc` on PostgreSQL 18 from `docker-compose.yml`: all pass (with `CONNECTION_TIMEOUT=PT10M`, because the machine was saturated). `serve` stops cleanly on SIGTERM and exits 0.
- `JdbcSeatStoreIT` (the identical contract class): 12 of 12, once on Testcontainers and once on the compose database. `JdbcHoldRaceIT` (20 races of 1,000 virtual threads, each `wins=10 losses=990`, and 100 sweep-versus-confirm repetitions): passed on Testcontainers.

- After the last code change (the log timestamp now prints the machine's time-zone offset instead of a fixed `Z`), `make lint` and `make test` (85 tests, coverage gate met) were rerun and pass.
- Technology notes in `tech/`: every link under "Further reading" was fetched and resolves (the notes were written against the pinned versions; a few statements were checked against the official documentation: JUnit 6 needs Java 17, Surefire and Failsafe default test-name patterns, JaCoCo's `argLine`, checkstyle's `MissingOverride`). A sample of the "Try it yourself" commands was run (the `repl` input, `javap`, `xmllint`, the Ruby YAML one-liner, `make help`, `git check-ignore`); the ones that need Docker or a network failure were not all run.

Pending capacity: run `make test-integration` as one command, and `make docker-build && docker run --rm ood-seat-holds:dev`, once the machine is quiet (on 2026-10-28 its load average was above 200, so the Docker checks were not rerun after Docker's disk problem was fixed; the shared-machine rule says to wait for a load under 30). The single-command run was not green on the overloaded machine: the PostgreSQL container took 5 to 8 minutes to start and timed out once, and one run was killed by an outside SIGTERM; the per-class runs above passed. The container image was not built.

## License

MIT, see [LICENSE](LICENSE). Copyright (c) 2026 Sumit Srivastava.
