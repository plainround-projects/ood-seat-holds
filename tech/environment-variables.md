# Environment variables and the `.env.example` file

## What it is

An **environment variable** is a named piece of text that the operating system hands to every program it starts. A program can read it by name: `ROOM_ID`, `LOG_LEVEL`, `HOME`. Variables are how you change a program's settings without changing its code, and without putting secrets (passwords, keys) into files that are committed. A `.env` file is a plain text file of `NAME=value` lines that a developer keeps locally to hold such settings. `.env.example` is its safe, committed twin: it lists every variable with harmless example values.

## Why this repository uses it

The production-grade rules say configuration comes from the environment with **validated defaults** (the twelve-factor way) and that secrets never go in the repository. So every setting here has a default that works out of the box, is checked when the program starts, and can be overridden from the shell, from CI, or from a Docker container.

## Where it appears

| Path | What it does with environment variables |
| --- | --- |
| `.env.example` | Documents every variable and its default. |
| `src/main/java/io/plainround/seatholds/interfaces/cli/AppConfig.java` | Reads and validates the settings (`STORE`, `HOLD_DURATION`, `DATABASE_URL`, ...). |
| `Makefile` | `-include .env` and `export` pass a developer's `.env` to every command. `JDBC_ENV` supplies defaults for the database variables. |
| `docker-compose.yml` | Uses `${DATABASE_PASSWORD:-...}` and `${POSTGRES_PORT:-...}`. |
| `*/Main.java` | The one place the program touches the real environment: `System.getenv()`. |

## How it works underneath

When a process starts, its parent gives it a **copy** of an environment: a list of `NAME=value` pairs. Changing it later in the child does not affect the parent. In a shell you set a variable for one command like this:

```bash
ROOM_ID=boardroom make run
```

and for the rest of the shell session with `export NAME=value`. Java reads the environment with `System.getenv()`, which returns a read-only map of all variables, or `System.getenv("NAME")` for one (`null` if missing). Values are always text; the program must convert and check them (a number, a time, a duration).

Docker containers start with a nearly empty environment; you give variables with `docker run -e NAME=value`. CI systems set them in the workflow. Because every runtime can set them, environment variables are the common language between a program and where it runs.

## Walkthrough of this repository's use

### The documented list

```bash
# Every variable is optional; the value shown is the default (the database settings apply to STORE=jdbc).
# memory or jdbc
STORE=memory
# How long a hold lasts, and how often the sweeper looks for expired ones (ISO-8601 durations).
HOLD_DURATION=PT10M
SWEEP_INTERVAL=PT30S
# Seats s-1 to s-N are added to the store at start-up (idempotent).
SEATS=10
# SEVERE, WARNING, INFO or FINE
LOG_LEVEL=INFO

# Local development database (docker-compose.yml). Placeholder password for a throwaway container only;
# use the environment or a secret store for anything real.
POSTGRES_PORT=55468
DATABASE_URL=jdbc:postgresql://localhost:55468/seatholds
DATABASE_USER=seatholds
DATABASE_PASSWORD=seatholds-local-only
POOL_SIZE=16
# How long a caller waits for a free connection before failing (ISO-8601 duration).
CONNECTION_TIMEOUT=PT30S
```

Lines starting with `#` are comments. Each `NAME=value` line shows a variable and its default. A reader copies the file to `.env` and edits what they need. **`.env` itself is in `.gitignore`**, so a real password could never be committed by accident (`git.md`).
The password here, `seatholds-local-only`, is a placeholder for a throwaway container on your own machine, and the file says so. A real deployment gets its password from its own environment or a secret store.

### Reading the environment at the edge

```java
int exitCode = new Cli(System.getenv(), System.out, System.err, shutdown).run(args);
```

`System.getenv()` is called once, in `Main`, and the resulting map is passed into the `Cli` object. Nothing deeper in the program touches the real environment. That makes every other class testable: a test passes `Map.of("ROOM_CAPACITY", "0")` instead of changing the machine's environment.

### Validating and defaulting

```java
public static AppConfig from(Map<String, String> env) {
  try {
    StoreKind store =
        StoreKind.valueOf(env.getOrDefault("STORE", "memory").toUpperCase(Locale.ROOT));
    DatabaseSettings database =
        store == StoreKind.JDBC
            ? new DatabaseSettings(
                env.get("DATABASE_URL"),
                env.get("DATABASE_USER"),
                env.get("DATABASE_PASSWORD"),
                Integer.parseInt(env.getOrDefault("POOL_SIZE", "16")),
                positive("CONNECTION_TIMEOUT", env.getOrDefault("CONNECTION_TIMEOUT", "PT30S")))
            : null;
    Duration holdFor = positive("HOLD_DURATION", env.getOrDefault("HOLD_DURATION", "PT10M"));
    Duration sweepEvery = positive("SWEEP_INTERVAL", env.getOrDefault("SWEEP_INTERVAL", "PT30S"));
    int seats = Integer.parseInt(env.getOrDefault("SEATS", "10"));
    if (seats < 1) {
      throw new IllegalArgumentException("SEATS must be at least 1, was " + seats);
    }
    String level = env.getOrDefault("LOG_LEVEL", "INFO").toUpperCase(Locale.ROOT);
    Logging.requireKnownLevel(level);
    return new AppConfig(store, database, holdFor, sweepEvery, seats, level);
  } catch (DateTimeParseException | IllegalArgumentException problem) {
    throw new ConfigurationException("invalid configuration: " + problem.getMessage());
```

- `StoreKind.valueOf(env.getOrDefault("STORE", "memory").toUpperCase(Locale.ROOT))` turns the text into one of the two allowed choices and fails on anything else.
- The database settings are required only when `STORE=jdbc`; otherwise `database` stays `null`.
- `positive("HOLD_DURATION", ...)` parses an ISO-8601 duration such as `PT10M` (ten minutes) and rejects zero or negative.
- `SEATS` and `POOL_SIZE` are parsed as whole numbers and range-checked. The log level is checked against the allowed names.
- All failures become one `ConfigurationException` with a message naming the variable, and `Cli` exits with code 2.

### In the `Makefile`

```make
-include .env
```
```make
export
```

`-include .env` pulls in the developer's local `.env` if it exists (the `-` means "no error if missing"), and `export` passes every Make variable on to the programs the recipes start.

## Try it yourself

1. `ROOM_CAPACITY=many make health` is not a seat setting: try `SEATS=0 make health`, which prints `invalid configuration: SEATS must be at least 1, was 0` and exits with status 2 (`echo $?`).
2. `printenv HOME` prints one variable; `printenv | head` shows the start of the whole environment.
3. `cat .env.example` and compare each variable with the table in the README.
4. `LOG_LEVEL=FINE make health 2>&1 | head` runs with a lower threshold (more log lines).
5. `env -i /usr/bin/printenv` runs `printenv` with an empty environment: it prints nothing. `env -i` clears the environment.

## Mistakes people make

- **Committing `.env`.** It is listed in `.gitignore`; only `.env.example` is committed.
- **Treating values as the right type.** Environment variables are text. This repository converts and checks each one before use.
- **No defaults,** so the program fails on a fresh machine. Every variable here is optional.
- **Silently ignoring a bad value.** Falling back to a default on a typo hides the mistake. Here a bad value stops the program with a clear message.
- **Putting secrets in logs.** `DatabaseSettings.toString()` leaves the password out, and a test checks it.

## Check yourself

1. *What is an environment variable?* A named text value the system gives to every program it starts.
2. *Why is `.env` ignored by Git but `.env.example` committed?* `.env` may hold local settings or secrets; the example documents names and safe defaults.
3. *What does `env.getOrDefault("X", "d")` return when `X` is missing?* `d`.
4. *Why is `System.getenv()` called only in `Main`?* So the rest of the program receives the settings as a plain map and can be tested without touching the real environment.
5. *What exit status does a bad setting give here?* 2.

## Further reading

- Java `System.getenv`: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#getenv()>
- The Twelve-Factor App, III. Config: <https://12factor.net/config>
