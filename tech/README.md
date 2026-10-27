# Technology notes for `ood-seat-holds`

These notes teach every technology this repository uses, from zero, through the repository's own files. They assume you have never used a terminal, Java, Maven, Docker, SQL or Git. Each file has the same nine parts: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's own code, things to try, common mistakes, five check-yourself questions and official documentation links.

## The files and their roles

| File | Role in this repository |
| --- | --- |
| [java.md](java.md) | The language: records, a sealed state type, pattern-matching `switch` with guards; the domain and the service. |
| [java-concurrency.md](java-concurrency.md) | Virtual threads, compare-and-set, latches, the scheduler and the graceful shutdown. |
| [java-logging.md](java-logging.md) | `System.Logger` and the one-line `key=value` log format. |
| [javac.md](javac.md) | The compiler and the `-Xlint:all -Werror` setting that is the type check. |
| [jdbc.md](jdbc.md) | Talking to PostgreSQL from Java: the conditional `UPDATE` that makes "sold once" true. |
| [hikaricp.md](hikaricp.md) | The connection pool that limits 1,000 threads to 16 connections. |
| [slf4j.md](slf4j.md) | The logging front door that HikariCP and Testcontainers use. |
| [sql.md](sql.md) | The scripts in `sql/` and the statements in the adapter. |
| [postgresql.md](postgresql.md) | The database server, `psql`, and why one `UPDATE` is atomic. |
| [docker-compose.md](docker-compose.md) | `docker-compose.yml`: the database in one command. |
| [testcontainers.md](testcontainers.md) | A real PostgreSQL started from the tests. |
| [maven.md](maven.md) | The build tool and `pom.xml`, including the integration profile. |
| [maven-wrapper.md](maven-wrapper.md) | `./mvnw`, which pins Maven 3.9.16 with no install. |
| [maven-enforcer.md](maven-enforcer.md) | Build rules: pinned plugins, converged dependencies. |
| [maven-surefire.md](maven-surefire.md) | The plugin that runs the unit tests. |
| [maven-failsafe.md](maven-failsafe.md) | The plugin that runs the `*IT` database tests. |
| [maven-dependency-plugin.md](maven-dependency-plugin.md) | Lists libraries for the audit and copies them to `target/lib`. |
| [junit.md](junit.md) | The test framework and the abstract contract test. |
| [junit-test-walkthrough.md](junit-test-walkthrough.md) | Every test class and test method in this repository: what each one checks and why. |
| [assertj.md](assertj.md) | Readable assertions. |
| [archunit.md](archunit.md) | Tests that enforce the layers, no concurrency in the domain, SQL only in the JDBC adapter. |
| [jacoco.md](jacoco.md) | Code coverage and the 95 percent gate. |
| [spotless-google-java-format.md](spotless-google-java-format.md) | The formatter. |
| [checkstyle.md](checkstyle.md) | The linter: Javadoc on the public surface, no TODOs. |
| [make.md](make.md) | The `Makefile`: `make up`, `make run-jdbc`, `make race`, `make test-integration`. |
| [bash-and-shell.md](bash-and-shell.md) | The shell commands in the Makefile, the Dockerfile and `mvnw`. |
| [python.md](python.md) | The one script, `scripts/audit.py`, that audits dependencies. |
| [osv.md](osv.md) | The vulnerability database the audit asks. |
| [docker.md](docker.md) | The `Dockerfile` and `.dockerignore`. |
| [github-actions.md](github-actions.md) | The CI workflow, with its database job. |
| [git.md](git.md) | The history and `.gitignore`. |
| [editorconfig.md](editorconfig.md) | Editor habits for files the formatter does not touch. |
| [xml.md](xml.md) | The format of `pom.xml` and `config/checkstyle.xml`. |
| [yaml.md](yaml.md) | The format of the CI workflow and the compose file. |
| [markdown.md](markdown.md) | The format of the README, ARCHITECTURE and the other documents. |
| [mermaid.md](mermaid.md) | The diagrams in `ARCHITECTURE.md`. |
| [environment-variables.md](environment-variables.md) | Settings from the environment, `.env.example`, and the database password rule. |

## Reading order

1. **The language:** `java.md`, then `java-concurrency.md`.
2. **The database:** `postgresql.md`, `sql.md`, `jdbc.md`, `hikaricp.md`, `docker-compose.md`.
3. **The build tool:** `maven-wrapper.md`, `maven.md`, `javac.md`.
4. **The tests:** `junit.md`, `junit-test-walkthrough.md`, `assertj.md`, `archunit.md`, `testcontainers.md`, `maven-surefire.md`, `maven-failsafe.md`, `jacoco.md`.
5. **The quality checks:** `spotless-google-java-format.md`, `checkstyle.md`, `maven-enforcer.md`, `maven-dependency-plugin.md`, `python.md`, `osv.md`.
6. **Running and delivering:** `make.md`, `bash-and-shell.md`, `environment-variables.md`, `java-logging.md`, `slf4j.md`, `docker.md`, `git.md`, `github-actions.md`.
7. **The file formats:** `xml.md`, `yaml.md`, `markdown.md`, `mermaid.md`, `editorconfig.md`.

## If you have ten minutes

Read these three: **`java-concurrency.md`** (the race and why compare-and-set, not a lock, is the fix), **`jdbc.md`** (the one `UPDATE` statement where "sold at most once" lives), and **`java.md`** (the seat as three states and pure functions).
