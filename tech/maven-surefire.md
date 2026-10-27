# Maven Surefire Plugin 3.6.1 (the test runner)

## What it is

Surefire is the Maven plugin that **finds and runs the tests**. A test is a method that checks one behavior and fails if the check is wrong. Surefire starts a fresh Java process, tells JUnit (`junit.md`) which test classes to run, collects the results, writes reports and fails the build if any test failed.

## Why this repository uses it

`make test` must run every unit, architecture and end-to-end test with one command and fail loudly. Surefire is Maven's built-in way to do that, bound to the `test` phase.

## Where it appears

| Path | What it does with Surefire |
| --- | --- |
| `pom.xml` | Pins the plugin version (3.6.1). It has no other settings: it works by convention. |
| `src/test/java/**/*Test.java` | The classes Surefire picks up. |
| `target/surefire-reports/` | Its output: one text and one XML report per test class. |
| `pom.xml` (`integration` profile) | The sister plugin Failsafe runs `*IT` classes (`maven-failsafe.md`). |

## How it works underneath

During the `test` phase Surefire:

1. Looks in `target/test-classes` for compiled classes whose names match its default patterns: `*Test`, `Test*`, `*Tests`, `*TestCase` (names ending in `IT` do not match; those belong to Failsafe).
2. Starts a **new JVM** (`java`) for the tests, with the class path made of the compiled code, the test classes and the test libraries. The JaCoCo agent, if present, is added to this process (`jacoco.md`).
3. In that JVM the **JUnit Platform** discovers the test methods and runs them; Surefire gathers the outcomes.
4. It prints `Tests run: N, Failures: F, Errors: E, Skipped: S` for each class and in total, writes the reports, and fails the build if there are failures or errors. A **failure** is a check that was wrong (an assertion); an **error** is an unexpected exception.

Abstract classes and classes without tests are skipped: `SeatStoreContract` is abstract on purpose, and runs only through its subclasses `InMemorySeatStoreTest` and `JdbcSeatStoreIT`.

## Walkthrough of this repository's use

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.6.1</version>
</plugin>
```

Only the version is set. Everything else is the default, which is deliberate: test files are named `*Test`, so no configuration is needed. The test libraries come from the dependencies in the POM (`maven.md`), the coverage agent arrives through `argLine` (`jacoco.md`), and the run is triggered by `./mvnw verify` (inside `make test`).

A run looks like this (shortened):

```text
[INFO] Running io.plainround.seatholds.domain.SeatTransitionsTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in ...
...
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
```

Each line `Tests run: ... -- in CLASS` is one test class. The total at the end is what the README quotes. The reports in `target/surefire-reports/*.txt` hold the same lines and, on a failure, the failing assertion and stack trace.

## Try it yourself

1. `./mvnw -B test` runs the tests only (no coverage check, which is in `verify`).
2. `./mvnw -B test -Dtest=SeatTransitionsTest` runs one class (`-Dtest` selects classes by name).
3. `ls target/surefire-reports | head` lists one report per class; open a `.txt` one.
4. In a scratch copy, change an expected value in a test and run `./mvnw -B test`: the report names the test and shows `expected` versus `but was`.
5. `./mvnw -B test -DskipTests` compiles the tests but does not run them (`make build` uses this).

## Mistakes people make

- **A test class named `Helper` or `Checks`** that does not match the patterns: it silently never runs. This repository names every test class `*Test` (or `*IT` for database tests).
- **Reading "BUILD SUCCESS" without the totals.** `Tests run: 0` also succeeds; check the count.
- **Skipping tests to get green** (`-DskipTests` in CI). Only `make build` and the Dockerfile skip them, on purpose.
- **Tests that depend on order.** Surefire does not promise one; every test here builds its own state.
- **Leaving a stuck test** with no timeout. The tests here use short bounded waits.

## Check yourself

1. *Which test class names does Surefire run by default?* Names matching `*Test`, `Test*`, `*Tests` or `*TestCase`.
2. *What is the difference between a failure and an error?* A failure is a wrong assertion; an error is an unexpected exception.
3. *In which JVM do the tests run?* A new JVM started by Surefire, separate from Maven's.
4. *Why does `make test` use `verify` and not `test`?* `verify` also runs the coverage check after the tests.
5. *What does `-DskipTests` do?* Skips running tests (they may still be compiled).

## Further reading

- Maven Surefire Plugin 3.6.1: <https://maven.apache.org/surefire/maven-surefire-plugin/>
- Inclusions and exclusions of tests: <https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html>
