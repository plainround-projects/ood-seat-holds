# Maven Failsafe Plugin 3.6.1 (integration tests)

## What it is

Failsafe is Surefire's sister plugin (`maven-surefire.md`). Surefire runs fast **unit tests**; Failsafe runs slower **integration tests**, which need something outside the program, here a real PostgreSQL in Docker. By default it picks up classes whose names end in `IT` (or start with `IT`, or end in `ITCase`). It splits the work in two goals, `integration-test` and `verify`, so that a failure is reported only after the environment has been cleaned up.

## Why this repository uses it

The database tests need Docker. `make test` must work without it, so those tests are kept out of the default build: they are named `*IT`, Surefire ignores them, and Failsafe is attached only when the Maven **profile** `integration` is switched on.

## Where it appears

| Path | What it does with Failsafe |
| --- | --- |
| `pom.xml` (`<profiles>`) | The `integration` profile that adds the plugin. |
| `Makefile` (`test-integration`) | `./mvnw -B -Pintegration verify`. |
| `src/test/java/io/plainround/seatholds/infrastructure/jdbc/JdbcSeatStoreIT.java`, `JdbcHoldRaceIT.java` | The classes it runs. |
| `target/failsafe-reports/` | Its reports. |

## How it works underneath

The lifecycle phases `pre-integration-test`, `integration-test`, `post-integration-test` and `verify` come after `package`. Failsafe's `integration-test` goal runs the `*IT` classes (it records results but does **not** fail the build yet); its `verify` goal then reads the results and fails the build if any test failed. That order lets tools that start and stop services around the tests do their cleanup even when a test fails. Here Testcontainers manages the database from inside the tests, but the pattern is the same.

A **profile** is a named group of build settings that applies only when requested with `-P`. Without `-Pintegration` the plugin is not part of the build.

## Walkthrough of this repository's use

```xml
<profiles>
  <profile>
    <!-- make test-integration: the contract and race tests against PostgreSQL in a container. -->
    <id>integration</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId>
          <version>3.6.1</version>
          <executions>
            <execution>
              <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
              </goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

- `<profile>` / `<id>integration</id>`: the name you pass to `-P`.
- The comment says what the profile is for.
- Inside, a `<build><plugins>` block adds `maven-failsafe-plugin` 3.6.1 with one execution binding two goals, `integration-test` and `verify`.

In the `Makefile`:

```make
	TESTCONTAINERS_RYUK_DISABLED=$${TESTCONTAINERS_RYUK_DISABLED:-true} ./mvnw -B -Pintegration verify
```

`./mvnw -B -Pintegration verify` runs everything up to `verify`: the normal unit tests through Surefire (and the JaCoCo check), then the `*IT` classes through Failsafe. `TESTCONTAINERS_RYUK_DISABLED=...` is set for the command (`testcontainers.md`).

A run's result lines look like this (one for each `*IT` class):

```text
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in io.plainround.seatholds.infrastructure.jdbc.JdbcSeatStoreIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in io.plainround.seatholds.infrastructure.jdbc.JdbcHoldRaceIT
```

## Try it yourself

1. `make test` does not run `*IT` classes: no `JdbcSeatStoreIT` line appears.
2. `make test-integration` does (Docker needed): both `IT` lines appear.
3. `ls target/failsafe-reports` after an integration run lists the reports.
4. `./mvnw -B -Pintegration verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=JdbcSeatStoreIT -Djacoco.skip=true` runs one IT class (`-Dit.test` selects Failsafe classes; `-Dtest=NoSuchTest` keeps Surefire from running the unit tests).
5. `./mvnw help:active-profiles -Pintegration` lists `integration` as active.

## Mistakes people make

- **Naming an integration test `*Test`.** Surefire would run it in every build, with or without Docker. Name it `*IT`.
- **Using `integration-test` as the last phase** and missing failures. Always run `verify`.
- **Forgetting `-P`.** Nothing runs and nothing complains; the `Makefile` target sets it.
- **Putting slow tests in the default build,** so developers stop running it.
- **Treating one slow-to-start container as a test failure.** Raise the start-up timeout instead.

## Check yourself

1. *What names does Failsafe run by default?* `*IT`, `IT*` and `*ITCase`.
2. *Why two goals?* `integration-test` runs the tests; `verify` fails the build afterwards, so cleanup can happen first.
3. *What does `-Pintegration` do?* Activates the profile that adds Failsafe.
4. *Why is the plugin not in the default build?* The tests need Docker; `make test` must not.
5. *Which command runs the database tests here?* `make test-integration`.

## Further reading

- Maven Failsafe Plugin 3.6.1: <https://maven.apache.org/surefire/maven-failsafe-plugin/>
- Maven profiles: <https://maven.apache.org/guides/introduction/introduction-to-profiles.html>
