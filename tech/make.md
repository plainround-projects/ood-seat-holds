# GNU Make (`Makefile`)

## What it is

Make is a command-line tool that runs named recipes. You write the recipes in a file called `Makefile`. Each recipe has a name (a **target**), a list of other targets it needs first (its **prerequisites**) and the shell commands to run (its **recipe**). You type `make test` and Make runs the recipe called `test`. Make was invented to rebuild files only when their sources changed. This repository uses it for a simpler reason: it is a short, universal way to name commands. The macOS command-line tools ship a Make (GNU Make 3.81).

## Why this repository uses it

The lesson's reader should need to remember a few words, not long Maven and Java command lines. `make run` builds and runs the scenarios, `make test` runs every test and the coverage gate, `make lint` runs the format and style checks. The same targets run in CI, so "works on my machine" and "works in CI" are the same commands.

## Where it appears

| Path | What it does with Make |
| --- | --- |
| `Makefile` | Defines every target. |
| `.github/workflows/ci.yml` | Runs `make check`, `make run` and `make audit` on a clean machine. |
| `README.md` | Tells the reader to use the targets. |

## How it works underneath

When you run `make test`:

1. Make reads `Makefile` from the current folder, top to bottom, and remembers every target, variable and recipe.
2. It looks up the target you named (`test`). If you named none, it uses the first target in the file.
3. It first makes each **prerequisite** (the words after the colon), recursively. For `run: build`, `build` runs before `run`.
4. It then runs the recipe lines, one by one. **Each line runs in its own new shell** (`/bin/sh`). A `cd` on one line does not carry to the next. If any line exits with a non-zero status, Make stops and reports `Error 1` (or the code). Exit status 0 means success, anything else means failure.
5. Make prints each command before it runs it, unless the line starts with `@`.

A recipe line must start with a **tab character**, not spaces. That is the most famous Make rule.

Make normally skips a target whose file already exists and is newer than its prerequisites. None of this repository's targets produce a file called `test` or `run`. The `.PHONY` line tells Make these names are only labels, so it always runs them.

## Walkthrough of this repository's use

The whole `Makefile` is shown below, in order, in pieces. Each piece is followed by what every line does. A line beginning with `#` is a comment (Make ignores it). A line beginning with a tab belongs to the recipe above it. Every target that is explained ends in a `## description` comment; the `help` target prints those.

### The header

```make
# make up | run | run-jdbc | race | test | test-integration | lint | audit | check | down      (make help lists everything)
MVN := ./mvnw -B -q
MAIN := io.plainround.seatholds.interfaces.cli.Main
JAVA_CP := target/classes:target/lib/*
POSTGRES_PORT ?= 55468
PSQL := docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds
```

- **Line 1** is a comment: a one-line reminder of the main targets. Make does nothing with it.
- **`MVN := ./mvnw -B -q`**: a **variable** is a name for a piece of text, and `:=` means "work out the value now and keep it". Anywhere the file writes `$(MVN)`, Make substitutes `./mvnw -B -q`. `./mvnw` is the Maven wrapper (`maven-wrapper.md`); `-B` is batch mode (plain log lines, no colors or progress bars) and `-q` is quiet (print only warnings and errors).
- **`MAIN := ...Main`**: the full name of the Java class that contains the `main` method, the place where the program starts. The recipes write `$(MAIN)` instead of repeating it.
- **`JAVA_CP := target/classes:target/lib/*`**: the **class path**, the list of places where Java looks for compiled code and libraries. `:` separates entries; `target/classes` is this project's compiled code; `target/lib/*` means every jar file in that folder (the libraries that Maven copied there). It is deliberately not called `CLASSPATH`: because of the `export` below, a variable of that name would be handed to every program as the environment variable `CLASSPATH`, which Java and other tools also read.
- **`POSTGRES_PORT ?= 55468`**: `?=` means "set this only if it is not already set", so you can override it from the shell: `POSTGRES_PORT=55470 make up`. A **port** is a number (0 to 65535) that tells the computer which program on the machine should receive a network connection; the database listens on this one, on your machine.
- **`PSQL := docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds`**: a command stored in a variable. `docker compose exec` runs a program inside a running container; `-T` means "do not allocate a terminal", which is needed when input is fed from a file; `db` is the name of the container's service in `docker-compose.yml`; `psql` is PostgreSQL's command-line client; `-v ON_ERROR_STOP=1` sets a psql variable that makes it stop at the first SQL error instead of carrying on; `-U seatholds` is the database user; the last `seatholds` is the database name (`postgresql.md`, `docker-compose.md`).

```make
-include .env
export
```

- **`-include .env`**: read a file called `.env` (if it exists) and treat it as more Makefile text. The leading `-` means "do not complain if the file is missing". A `.env` file holds `NAME=value` lines (`environment-variables.md`).
- **`export`** with no names: send every Make variable to every program that a recipe starts, as an **environment variable** (a named piece of text a process inherits). Together these two lines let a reader put settings in `.env` and have them reach the program.

### `.PHONY` and `help`

```make
.PHONY: help build up db queries psql run run-jdbc race serve health lint format test test-integration audit check docker-build down clean

help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort
```

- **`.PHONY: ...`** lists targets that are only labels and not names of files. Without it, a file called `test` in the folder would make `make test` say "up to date" and do nothing. Listing a name here tells Make to always run its recipe. Every target of this file is listed.
- **`help: ## list the targets`**: the target `help`, no prerequisites, and a `## ...` comment that is its description.
- **The `help` recipe** (the line starting with a tab): `@` hides the command itself. `grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST)` prints every line of the Makefile that starts with a target name (`[a-z-]+`: lower-case letters and hyphens), a colon, and later the text `## `; `-E` turns on extended patterns; `$(MAKEFILE_LIST)` is a built-in variable holding the Makefile's own name. `| sed -E 's/:.*## /\t/'` pipes (`|`) that into `sed`, a text editor for streams, which replaces everything from the colon through `## ` with a tab character (`\t`), leaving `name<TAB>description`. `| sort` puts the lines in alphabetical order. That is why `make help` shows a sorted menu.

### `build`

```make
build:
	$(MVN) -DskipTests package
```

`build` has no `##` comment, so `make help` does not list it, but other targets need it. Its recipe expands to `./mvnw -B -q -DskipTests ...`. `-DskipTests` sets a **property** (a named setting passed to Maven with `-D`) that tells the test plugin to skip tests, because this target only wants the code built.
`package` is the Maven step that compiles and then makes the jar file; the copy-the-libraries step bound to it fills `target/lib` (`maven-dependency-plugin.md`).

### `up`

```make
up: ## start PostgreSQL in a container and apply sql/001_schema.sql and sql/002_seed.sql
	docker compose up -d --wait
	$(MAKE) --no-print-directory db
```

`up` starts the database. `docker compose up -d --wait` starts the services listed in `docker-compose.yml` (`docker-compose.md`): `-d` runs them in the background (detached), and `--wait` makes the command wait until the container's health check says the service is ready. Then `$(MAKE) --no-print-directory db` runs the `db` target below. `$(MAKE)` is the right way to start another Make from inside a recipe (it keeps the settings of this one); `--no-print-directory` hides Make's "entering directory" lines.

### `db`, `queries` and `psql`

```make
db: ## apply the numbered SQL scripts in order (DDL, then the seed rows)
	$(PSQL) < sql/001_schema.sql
	$(PSQL) < sql/002_seed.sql

queries: ## run sql/003_queries.sql: seats per state, who holds what, expired holds
	$(PSQL) < sql/003_queries.sql

psql: ## open a psql session on the database
	docker compose exec db psql -U seatholds seatholds
```

- `db` feeds the two numbered SQL files into `psql`, one after the other, in order (`sql.md`). The `<` is **input redirection**: the shell opens the file and sends its content to the command's input, as if you had typed it. Because `$(PSQL)` uses `-T` (no terminal), input from a file works.
- `queries` does the same with the file of example queries, `003_queries.sql`.
- `psql` opens an interactive session: there is no `-T` here, so the command gets a terminal and waits for you to type. `exit` or Ctrl-D ends it.

### `run`

```make
run: build ## the lesson's scenarios on the in-memory store, and the failure they guard against
	java -cp "$(JAVA_CP)" $(MAIN) scenario
```

`run: build` says "run needs build first", so `make run` always compiles first. The recipe then starts Java: `java` runs a program; `-cp` (class path) says where the compiled code is (`"$(JAVA_CP)"` in quotes, because the `*` must reach Java and not be expanded by the shell); `$(MAIN)` names the class to start; and `scenario` is the first argument that class receives, which tells it to run the lesson's worked scenarios.

### `run-jdbc` and `race`

```make
JDBC_ENV = STORE=jdbc DATABASE_URL=$${DATABASE_URL:-jdbc:postgresql://localhost:$(POSTGRES_PORT)/seatholds} DATABASE_USER=$${DATABASE_USER:-seatholds} DATABASE_PASSWORD=$${DATABASE_PASSWORD:-seatholds-local-only}

run-jdbc: build ## the same scenarios on PostgreSQL (make up first)
	env $(JDBC_ENV) java -cp "$(JAVA_CP)" $(MAIN) scenario

RUNS ?= 20
race: build ## race 1000 customers for 10 seats RUNS times (default 20); add STORE=jdbc after make up
	env $(if $(filter jdbc,$(STORE)),$(JDBC_ENV)) java -cp "$(JAVA_CP)" $(MAIN) race $(RUNS) 1000
```

- **Line 36, `JDBC_ENV = ...`**: a variable with `=` (not `:=`), so its value is worked out each time it is used. It holds four settings for the program: `STORE=jdbc` (use the PostgreSQL store), `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD`. Inside a Makefile `$` begins a Make variable, so a `$` meant for the shell is written `$$`; Make then passes `${DATABASE_URL:-jdbc:...}` to the shell, which means "the value of `DATABASE_URL`, or this default if it is unset or empty". So a reader's own settings win, and otherwise the local defaults apply. The password `seatholds-local-only` is a placeholder for the local container only, never a real secret.
- **`run-jdbc: build`**: `env VAR=value ... java ...`: `env` sets those variables for that one command and then starts it. So the same scenarios run on PostgreSQL.
- **Line 41, `RUNS ?= 20`**: the number of repeated races, overridable (`make race RUNS=5`).
- **`race: build`**: `$(if $(filter jdbc,$(STORE)),$(JDBC_ENV))` is Make's `if` function: `filter jdbc,$(STORE)` yields `jdbc` only when you wrote `STORE=jdbc`; if the result is not empty, the text `$(JDBC_ENV)` is inserted, otherwise nothing. So `make race` uses the in-memory store and `make race STORE=jdbc` uses PostgreSQL. The command line ends `race $(RUNS) 1000`: the program's `race` command, run `RUNS` times, with 1000 customers (the lesson's numbers: 1000 customers for 10 seats).

### `serve` and `health`

```make
serve: build ## sweep expired holds every SWEEP_INTERVAL until Ctrl-C, then stop cleanly
	java -cp "$(JAVA_CP)" $(MAIN) serve

health: build ## exit 0 when the configuration and the store are usable
	java -cp "$(JAVA_CP)" $(MAIN) health
```

`serve` starts the long-running mode: it sweeps expired holds every `SWEEP_INTERVAL` until you press Ctrl-C (the signal `INT`), then stops cleanly (`java-concurrency.md`). `health` runs the `health` command, which exits with status 0 only when the configuration and the store are usable.

### `lint` and `format`

```make
lint: ## format check, compiler lint (-Xlint:all -Werror), checkstyle
	$(MVN) spotless:check
	$(MVN) -DskipTests compile checkstyle:check

format: ## apply google-java-format
	$(MVN) spotless:apply
```

`lint` has two recipe lines, so two Maven runs. Each line of a recipe runs in its own shell and Make stops at the first line that fails. The first, `spotless:check` (plugin `spotless`, goal `check`), fails if any file is not in the standard format. The second compiles with every compiler warning turned into an error (`-Xlint:all -Werror`, set in `pom.xml`) and then runs `checkstyle:check`. `format` runs `spotless:apply`, which rewrites the files into the standard format. See `spotless-google-java-format.md`, `checkstyle.md` and `javac.md`.

### `test`

```make
test: ## unit, architecture and end-to-end tests with the domain and application coverage gate (no Docker needed)
	./mvnw -B verify
```

`test` runs `./mvnw -B verify`: compile, run every test, and run the coverage check (`jacoco.md`). It does not use `$(MVN)` because it wants the test output visible (no `-q`). `verify` is the Maven stage that comes after `test` and `package`.

### `test-integration`

```make
test-integration: ## unit tests plus the contract and race tests against PostgreSQL in a container (Docker needed)
	TESTCONTAINERS_RYUK_DISABLED=$${TESTCONTAINERS_RYUK_DISABLED:-true} ./mvnw -B -Pintegration verify
```

The same, and in addition the tests that need a real database in Docker. `TESTCONTAINERS_RYUK_DISABLED=$${...:-true}` sets an environment variable for this one command: `$$` reaches the shell as `$`, and `:-true` keeps your own value if you set one, otherwise `true`. It switches off Testcontainers' helper container named Ryuk (`testcontainers.md`). `-Pintegration` turns on the Maven **profile** (a named group of optional settings) called `integration`, which adds the failsafe plugin (`maven-failsafe.md`).

### `audit` and `check`

```make
audit: ## check the resolved dependencies for known vulnerabilities (OSV database)
	$(MVN) dependency:list -DoutputFile=target/dependencies.txt -DappendOutput=false
	python3 scripts/audit.py

check: lint test ## everything that needs no Docker, except the audit
```

- `audit` first asks Maven to write the list of resolved libraries to a file: `dependency:list -DoutputFile=target/dependencies.txt -DappendOutput=false` (the second property says: replace the file, do not append). Then `python3 scripts/audit.py` runs the Python script that checks that list against a vulnerability database (`python.md`, `osv.md`).
- `check: lint test` has no recipe of its own: it only lists two prerequisites, so `make check` runs `lint` and then `test`.

### `docker-build`

```make
docker-build: ## build the container image
	docker build -t ood-seat-holds:dev .
```

`docker build -t NAME:dev .` builds a container image from the `Dockerfile` in the current folder (the `.`) and tags it with the name `NAME:dev` (`-t` is "tag"; `docker.md`).

### `down` and `clean`

```make
down: ## stop PostgreSQL and delete its data
	docker compose down -v

clean: down
	$(MVN) clean
```

`down` runs `docker compose down -v`: stop the containers and delete them together with their **volumes** (the disk space that holds the database's data), so the next `make up` starts empty. `clean: down` first runs `down`, then `./mvnw -B -q clean` deletes `target/`.

## Try it yourself

1. `make help` prints each target with its description, sorted. That is the `grep | sed | sort` pipeline at work.
2. `make -n test` (`-n` is "dry run") prints the commands for `test` without running them. Try it on `make -n check` to see that `check` expands to the commands of `lint` and `test`.
3. `make run` builds, then runs the scenario. Notice the line `./mvnw -B -q -DskipTests ...` is printed before the program's output, because Make echoes each command.
4. `make nonsense` prints `No rule to make target 'nonsense'`, and exits with a non-zero status (`echo $?` shows `2`).
5. `MAIN=something make -n run` shows that a variable set on the command line replaces the one in the file.

## Mistakes people make

- **Spaces instead of a tab** at the start of a recipe line. Make answers "missing separator". This repository's `.editorconfig` marks the file as tab-indented (`editorconfig.md`).
- **Expecting `cd` to carry over** to the next recipe line. Each line is a separate shell.
- **A single `$` in a shell variable.** Make eats the first `$`; write `$$`. The `JDBC_ENV` and `test-integration` lines do this.
- **Forgetting `.PHONY`** for a label target; if a file called `test` ever appeared, `make test` would say "up to date" and do nothing. This repository lists every label.
- **Slow recipes that hide errors.** A pipeline's exit status is that of its last command only; the recipes here avoid long pipelines for anything that must fail loudly.

## Check yourself

1. *What does `run: build` mean?* `build` is a prerequisite of `run`, so Make runs `build` first, then `run`.
2. *Why does each recipe line start with a tab?* It is Make's syntax: lines that start with a tab are the commands of the target above.
3. *What does `@` at the start of a recipe line do?* It stops Make from printing the command before it runs.
4. *Why write `$$` in a recipe?* Make treats `$` as its own variable marker; `$$` produces a literal `$` for the shell.
5. *What does `make check` run?* The prerequisites `lint` and `test`, in that order.

## Further reading

- GNU Make manual: <https://www.gnu.org/software/make/manual/make.html> (this repository is written for GNU Make 3.81, the macOS version, and works on newer ones)
- Automatic variables and functions (`filter`, `if`): <https://www.gnu.org/software/make/manual/html_node/Functions.html>
