# Bash and the shell (`sh`)

## What it is

A **shell** is the program that reads the commands you type in a terminal and runs them. **Bash** is the most common shell on Linux and was the default on macOS for years (macOS now defaults to **zsh**, which behaves the same for everything in this repository). `sh` is the name of the plain, older, smaller shell language that Bash and zsh both understand. A **shell script** is a text file of shell commands run in order.

This repository has no hand-written `.sh` file, but shell is everywhere in it: every line of a `Makefile` recipe is a shell command, the `Dockerfile` has a shell loop, the Maven wrapper `mvnw` is a 290-line shell script, and the CI workflow runs shell commands.

## Why this repository uses it

Shell is the glue. It starts programs (Maven, Java, Docker), passes them settings through environment variables, joins them with pipes, and decides what to do based on whether a command succeeded. Understanding the few shell features used here lets a reader follow every recipe and the wrapper script.

## Where it appears

| Path | What it does with shell |
| --- | --- |
| `Makefile` | Each recipe line is run by `/bin/sh -c "<line>"`. Includes the `grep \| sed \| sort` pipeline of `make help`. |
| `Dockerfile` | A `RUN for attempt in 1 2 3 4; do ...; done` retry loop (`docker.md`). |
| `mvnw` | The Maven wrapper script: shell functions, `case`, `while read`, traps, `exec` (`maven-wrapper.md`). |
| `.github/workflows/ci.yml` | Each `run:` step is a shell command. |

## How it works underneath

**Process and arguments.** When you type `ls -l src`, the shell finds a program named `ls`, starts it as a new **process**, and gives it the **arguments** `-l` and `src`. The shell splits your line into words at spaces. Quotes keep words together: `"a b"` is one argument.

**Exit status.** Every program ends with a number. `0` means success; anything else means failure. The shell keeps the last one in `$?`. This is how `make` knows a recipe failed.

**Joining commands.** `A && B` runs B only if A succeeded. `A || B` runs B only if A failed. `A; B` runs both. `A | B` (a **pipe**) connects A's output to B's input, so they run at the same time.

**Redirection.** `cmd > file` writes the output to a file. `cmd < file` reads input from a file. `2>&1` or `>&2` moves messages between the two output streams: **standard output** (normal results, stream 1) and **standard error** (problems, stream 2).

**Variables.** `name=value` sets a shell variable; `$name` or `${name}` reads it. An **environment variable** is a variable that is also handed to every program the shell starts (`environment-variables.md`). Useful forms: `${x:-default}` ("x, or `default` if x is unset or empty"), `${x-}` ("x, or empty if unset", safe under `set -u`), `${x##*/}` (x with everything up to the last `/` removed), `${x%.*}` (x without the last dot and what follows).

**Command substitution.** `$(cmd)` runs `cmd` and puts its output in place.

## Walkthrough of this repository's use

### The `make help` pipeline (in the `Makefile`)

```make
help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort
```

The shell sees: `grep -E '^[a-z-]+:.*## ' Makefile | sed -E 's/:.*## /\t/' | sort`. `grep -E PATTERN FILE` prints lines that match the **regular expression** (a pattern language for text): `^` start of line, `[a-z-]+` one or more lowercase letters or hyphens, `:` a colon, `.*` anything, `## ` those characters. The single quotes stop the shell from touching the pattern. `sed -E 's/OLD/NEW/'` replaces the first match of OLD with NEW on each line; here OLD is `:.*## ` (from the first colon to the `## `) and NEW is a tab (`\t`). `sort` orders the lines. Three processes, joined by two pipes, no temporary files.

### Default values and one-command environment (in the `Makefile`)

```make
JDBC_ENV = STORE=jdbc DATABASE_URL=$${DATABASE_URL:-jdbc:postgresql://localhost:$(POSTGRES_PORT)/seatholds} DATABASE_USER=$${DATABASE_USER:-seatholds} DATABASE_PASSWORD=$${DATABASE_PASSWORD:-seatholds-local-only}
```
```make
	TESTCONTAINERS_RYUK_DISABLED=$${TESTCONTAINERS_RYUK_DISABLED:-true} ./mvnw -B -Pintegration verify
```

`${DATABASE_URL:-jdbc:postgresql://localhost:55468/seatholds}` is "use my `DATABASE_URL` if I set one, else this local address". Make needs `$$` to pass one `$` through (`make.md`). `VAR=value program` (second line) sets a variable only for that one program. `env A=1 B=2 program` does the same through the `env` command; `env` is used for `run-jdbc` because the settings sit in a Make variable that expands to several `A=1` words.

```make
	$(PSQL) < sql/001_schema.sql
```

`<` makes the shell open the SQL file and feed it to the command's standard input. The command (`docker compose exec -T db psql ...`) reads the SQL from there and runs it.

### The retry loop (in the `Dockerfile`)

```docker
RUN for attempt in 1 2 3 4; do ./mvnw -B -q -DskipTests package && exit 0; echo "package failed (attempt $attempt), retrying"; sleep 10; done; exit 1
```

Read it as shell:

- `for attempt in 1 2 3 4; do ... done` runs the body four times with `attempt` set to 1, 2, 3, 4.
- `./mvnw -B -q -DskipTests package && exit 0` runs Maven; if it succeeded, `exit 0` ends the whole `RUN` successfully right away.
- `echo "package failed (attempt $attempt), retrying"` runs only if Maven failed (the `&&` chain stopped). The double quotes let `$attempt` be replaced by its value.
- `sleep 10` waits ten seconds before the next try.
- `exit 1` after the loop runs only if all four tries failed, so the image build fails.

Why: downloading from Maven Central over a shared network sometimes drops a connection. One failure should not fail the build; four in a row should.

### `mvnw`: a real script

`mvnw` uses nearly every feature above, and a few more: `set -euf` (strict mode), functions (`die() { ...; }`), `case ... esac` (match a value against patterns), `while IFS="=" read -r key value; do ... done < file` (read a file line by line), `trap clean HUP INT TERM EXIT` (run `clean` when the script ends or is interrupted), a here-document (`cat >file <<-END ... END` writes several lines into a file), `command -v NAME` (is this program available?), `mktemp -d` (make a temporary folder), and `exec program "$@"` (replace the script with the program, passing all arguments unchanged). Each is explained where it is used in `maven-wrapper.md`.

### `run:` steps in CI

```yaml
- run: make check
```

GitHub starts a shell on the CI machine and runs this line. If the exit status is not 0, the step and the job fail.

## Try it yourself

1. `echo $?` right after `ls` prints `0`; after `ls /no/such/folder` it prints a non-zero number (`1` or `2`).
2. `true && echo yes`, `false && echo yes`, `false || echo no`: only the first and the third print.
3. `make help | head -3`: the pipe `|` and `head` show the first three lines of the table of targets.
4. `echo "attempt $(echo 3)"` shows command substitution; `echo 'attempt $(echo 3)'` (single quotes) shows that single quotes stop all substitution.
5. `FOO=bar sh -c 'echo $FOO'` prints `bar`; then `echo $FOO` prints an empty line: the variable existed only for that command.

## Mistakes people make

- **Unquoted variables** (`rm $dir` when `$dir` has a space). The shell splits it into two words. The scripts here quote (`"$@"`, `"$scriptDir/..."`).
- **Ignoring exit status.** Without `-e` or `&&`, a failed step is skipped over silently. `mvnw` sets `-e`; the `Dockerfile` loop checks the status explicitly.
- **Mixing up `'` and `"`.** Single quotes keep text literally; double quotes allow `$variable` and `$(command)`.
- **Using Bash-only features in `sh` scripts.** `mvnw` sticks to plain `sh` so it works on any Unix.
- **Pipelines hide failures** (only the last command's status counts), which is why `make audit` runs its two commands as separate lines.

## Check yourself

1. *What does exit status `0` mean?* The command succeeded; any other number means it failed.
2. *What does `A && B` do?* Runs B only if A succeeded.
3. *What is the difference between `'$x'` and `"$x"`?* Single quotes print `$x` literally; double quotes replace it with the variable's value.
4. *What does `${DATABASE_URL:-fallback}` mean?* The value of `DATABASE_URL`, or `fallback` if it is unset or empty.
5. *What does `exec` do?* Replaces the current process with another program, so there is no leftover shell and the program's exit status becomes the script's.

## Further reading

- Bash reference manual: <https://www.gnu.org/software/bash/manual/bash.html>
- POSIX shell language (what plain `sh` guarantees): <https://pubs.opengroup.org/onlinepubs/9799919799/utilities/V3_chap02.html>
