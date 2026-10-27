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

### The header: variables

```make
MVN := ./mvnw -B -q
MAIN := io.plainround.seatholds.interfaces.cli.Main
```

A **variable** is a name for a piece of text. `:=` assigns it once, now. You use it as `$(MVN)`. Here:

- `MVN` is the Maven command with two flags: `-B` (batch mode: plain log output) and `-q` (quiet: print only warnings and errors). `./mvnw` is the Maven wrapper (`maven-wrapper.md`).
- `MAIN` is the full name of the Java class that holds the `main` method, so the recipes can say `$(MAIN)`.

```make
JAVA_CP := target/classes:target/lib/*
POSTGRES_PORT ?= 55468
PSQL := docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds

-include .env
export
```

- `JAVA_CP`: the **class path**, the list of places Java looks for compiled code and libraries. `:` separates entries; `target/lib/*` means every jar in that folder. It is passed to Java explicitly with `-cp`. (It is deliberately not called `CLASSPATH`: the `export` below would then hand it to every program as the environment variable `CLASSPATH`, which Java and other tools also read.)
- `POSTGRES_PORT ?= 55468`: `?=` means "set this only if it is not already set", so you can override it from the shell (`POSTGRES_PORT=55470 make up`).
- `PSQL := docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds`: a command stored in a variable. It runs the `psql` program inside the database container (see `postgresql.md` and `docker-compose.md`). `-T` means "do not allocate a terminal", which is needed when input is piped in from a file. `-v ON_ERROR_STOP=1` makes `psql` stop at the first SQL error.
- `-include .env` reads an optional file `.env` and treats it as more Make code; the leading `-` means "no error if the file is missing". `export` with no names sends every Make variable to the programs the recipes start, as **environment variables** (see `environment-variables.md`). Together these let a reader put settings in `.env`.

### `.PHONY` and `help`

```make
.PHONY: help build up db queries psql run run-jdbc race serve health lint format test test-integration audit check docker-build down clean
```

`.PHONY` lists the targets that are labels, not files.

```make
help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort
```

This is a small trick that makes `make help` list the targets. Every target in this file has a `## comment` after its name. The recipe:

- `@` hides the command itself.
- `grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST)` prints every line that starts with a target name (`[a-z-]+`, letters and hyphens), a colon and later `## `. `-E` turns on extended patterns. `$(MAKEFILE_LIST)` is a built-in variable holding the Makefile's name.
- `| sed -E 's/:.*## /\t/'` pipes (`|`) that output into `sed`, which replaces everything from the colon through `## ` with a tab, leaving `name<TAB>description`.
- `| sort` puts the lines in alphabetical order.

### Build, run and the other targets

```make
build:
	$(MVN) -DskipTests package
```

`build` compiles the code. `$(MVN) -DskipTests ...` expands to `./mvnw -B -q -DskipTests ...`. `-DskipTests` sets a property that tells the test plugin to skip tests, because this target only wants the code compiled (or packaged).

```make
run: build ## the lesson's scenarios on the in-memory store, and the failure they guard against
	java -cp "$(JAVA_CP)" $(MAIN) scenario
```

`run: build` says "run needs build first", so `make run` always compiles first. The recipe then starts Java: `java` runs a program, `-cp` gives the class path, `$(MAIN)` names the class to start, and `scenario` is the first argument that class receives.

```make
up: ## start PostgreSQL in a container and apply sql/001_schema.sql and sql/002_seed.sql
	docker compose up -d --wait
	$(MAKE) --no-print-directory db

db: ## apply the numbered SQL scripts in order (DDL, then the seed rows)
	$(PSQL) < sql/001_schema.sql
	$(PSQL) < sql/002_seed.sql

queries: ## run sql/003_queries.sql: seats per state, who holds what, expired holds
	$(PSQL) < sql/003_queries.sql

psql: ## open a psql session on the database
	docker compose exec db psql -U seatholds seatholds
```

`up` starts the database: `docker compose up -d --wait` starts the services in `docker-compose.yml` in the background (`-d`) and waits until their health check passes (`--wait`); then `$(MAKE) --no-print-directory db` runs the `db` target again through Make itself (`$(MAKE)` is the right way to start a sub-make; `--no-print-directory` hides its "entering directory" lines). `db` feeds the SQL files into `psql` one after the other with `<` (the shell reads the file and sends it to the command's input). `queries` does the same for the queries file. `psql` opens an interactive session (no `-T`, so it gets a terminal).

```make
run: build ## the lesson's scenarios on the in-memory store, and the failure they guard against
	java -cp "$(JAVA_CP)" $(MAIN) scenario

JDBC_ENV = STORE=jdbc DATABASE_URL=$${DATABASE_URL:-jdbc:postgresql://localhost:$(POSTGRES_PORT)/seatholds} DATABASE_USER=$${DATABASE_USER:-seatholds} DATABASE_PASSWORD=$${DATABASE_PASSWORD:-seatholds-local-only}

run-jdbc: build ## the same scenarios on PostgreSQL (make up first)
	env $(JDBC_ENV) java -cp "$(JAVA_CP)" $(MAIN) scenario

RUNS ?= 20
race: build ## race 1000 customers for 10 seats RUNS times (default 20); add STORE=jdbc after make up
	env $(if $(filter jdbc,$(STORE)),$(JDBC_ENV)) java -cp "$(JAVA_CP)" $(MAIN) race $(RUNS) 1000

serve: build ## sweep expired holds every SWEEP_INTERVAL until Ctrl-C, then stop cleanly
	java -cp "$(JAVA_CP)" $(MAIN) serve

health: build ## exit 0 when the configuration and the store are usable
	java -cp "$(JAVA_CP)" $(MAIN) health
```

- `run` starts the Java program on the in-memory store.
- `JDBC_ENV` uses `=`, not `:=`: it is expanded each time it is used. Inside it, `$$` is how you write one literal `$` for the shell, so `$${DATABASE_URL:-...}` reaches the shell as `${DATABASE_URL:-...}`, "the variable, or this default if it is unset or empty".
- `run-jdbc` runs the program with `env VAR=value ... java ...`: `env` sets those variables for that one command.
- `race` has `RUNS ?= 20`, then `$(if $(filter jdbc,$(STORE)),$(JDBC_ENV))`: a Make function that adds the JDBC settings only when you wrote `STORE=jdbc`. `filter jdbc,$(STORE)` is non-empty only if `STORE` is `jdbc`.
- `serve` and `health` run the matching commands of the program.

```make
lint: ## format check, compiler lint (-Xlint:all -Werror), checkstyle
	$(MVN) spotless:check
	$(MVN) -DskipTests compile checkstyle:check
```
```make
format: ## apply google-java-format
	$(MVN) spotless:apply
```

`lint` has two recipe lines, so two Maven runs: `spotless:check` (plugin `spotless`, goal `check`) fails if any file is not formatted; the second compiles with warnings as errors and then runs checkstyle. `format` rewrites files into the standard format. See `spotless-google-java-format.md`, `checkstyle.md` and `javac.md`.

```make
test: ## unit, architecture and end-to-end tests with the domain and application coverage gate (no Docker needed)
	./mvnw -B verify
```

`test` runs `./mvnw -B verify`: compile, run all tests and run the coverage check (`jacoco.md`). It does not use `$(MVN)` because it wants the test output visible (no `-q`).

```make
test-integration: ## unit tests plus the contract and race tests against PostgreSQL in a container (Docker needed)
	TESTCONTAINERS_RYUK_DISABLED=$${TESTCONTAINERS_RYUK_DISABLED:-true} ./mvnw -B -Pintegration verify
```

`test-integration` also runs the database tests. `TESTCONTAINERS_RYUK_DISABLED=...` sets an environment variable for this command only; `$${X:-true}` keeps your own value if you set one, else `true`. `-Pintegration` turns on the Maven profile that adds the failsafe plugin (`maven-failsafe.md`).

```make
audit: ## check the resolved dependencies for known vulnerabilities (OSV database)
	$(MVN) dependency:list -DoutputFile=target/dependencies.txt -DappendOutput=false
	python3 scripts/audit.py
```

`audit` first asks Maven to write the list of resolved libraries to a file (`dependency:list -DoutputFile=...`), then runs the Python script that checks that list against a vulnerability database (`python.md`, `osv.md`).

```make
check: lint test ## everything that needs no Docker, except the audit
```

`check: lint test` has no recipe of its own: it only lists two prerequisites, so `make check` runs `lint` then `test`.

```make
docker-build: ## build the container image
	docker build -t ood-seat-holds:dev .
```

`docker build -t NAME:dev .` builds the image from the `Dockerfile` in the current folder (`.`) and tags it `NAME:dev` (`docker.md`).

```make
down: ## stop PostgreSQL and delete its data
	docker compose down -v

clean: down
```
```make
clean: down
	$(MVN) clean
```

`down` stops the database and deletes its data (`-v` removes the volumes). `clean: down` first runs `down`, then `$(MVN) clean` deletes `target/`.

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
