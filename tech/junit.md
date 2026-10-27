# JUnit 6.0.3 (JUnit Jupiter)

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit that you write tests with; the **JUnit Platform** is the part that finds and runs them (the engine Surefire uses, `maven-surefire.md`). This repository uses JUnit **6.0.3**, the current line (JUnit 6 needs Java 17 or newer and keeps the Jupiter API of version 5).

An **annotation** is a label written with `@` before a method or class, like `@Test`. It does nothing by itself; tools read it.

## Why this repository uses it

The template says tests "read as the lesson's requirements": every test method here has a long, sentence-like name that states one behavior, and all of them run with one command. JUnit gives the structure; AssertJ (`assertj.md`) gives the readable checks.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `pom.xml` | Declares `junit-jupiter` and `junit-platform-launcher` (version `${junit.version}` = 6.0.3), scope `test`. |
| `src/test/java/**` | Every test class. This repository has 85 unit tests (plus 14 database tests), named after behavior. |
| `target/surefire-reports/` | Where the results are written. |

## How it works underneath

1. Surefire starts a JVM and gives the **JUnit Platform** the test classes.
2. The Platform asks the **Jupiter engine** to **discover** tests: it scans classes for methods marked `@Test`.
3. For **each** test method, Jupiter creates a **new instance** of the test class, runs the `@BeforeEach` methods, runs the test method, then the `@AfterEach` methods. A new instance each time means one test cannot leave behind state that another sees.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError` (a check was wrong) and **errors** if it throws anything else.
5. Before the first test of a class, `@BeforeAll` methods (which must be `static`) run once.
6. Tests may take **parameters** that JUnit supplies. `@TempDir Path folder` gives each test a new empty temporary folder and deletes it afterwards.

Tests are plain Java: there is no special language. A test class has no `public` modifier here because JUnit 5 and 6 allow package-private test classes and methods, which keeps the tests out of the public API.

## Walkthrough of this repository's use

### The shape of a test: one behavior, one sentence

```java
@Test
void confirmingAnExpiredHoldGivesAReasonNotASale() {
  var expiredHold = new Held(ADA, clock.now().minus(Duration.ofMinutes(1)));

  var outcome = SeatTransitions.confirm(expiredHold, ADA, clock);

  assertThat(outcome).isEqualTo(new Reason("hold expired"));
}
```

- `@Test` marks the method as a test.
- The name is a sentence about behavior. When it fails, the report reads like a broken requirement.
- The body follows **arrange, act, assert**: build the input, call the code, check the result. `assertThat`, `assertThatThrownBy` and `isEqualTo` come from AssertJ.

### `@BeforeEach`: fresh state for every test

```java
private SeatStore store;
private SeatId seat;

/** A new, empty store of the adapter under test. */
protected abstract SeatStore newStore();

@BeforeEach
void freshStoreWithOneFreeSeat() {
  store = newStore();
  seat = new SeatId("A1-" + System.nanoTime());
  store.add(seat);
}
```

`@BeforeEach` runs before every test method: it asks the subclass for a new store (`newStore()` is `abstract`) and adds one free seat with a unique id, so every contract test starts from a known state.

### `@TempDir`: a throwaway folder

Other `@TempDir` use: none here; the database tests use a real PostgreSQL instead (`testcontainers.md`).

### An abstract test class as a contract

```java
package io.plainround.seatholds.infrastructure;

import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;

class InMemorySeatStoreTest extends SeatStoreContract {

  @Override
  protected SeatStore newStore() {
    return new InMemorySeatStore();
  }
}
```

`SeatStoreContract` is an **abstract class** full of tests: it has `@Test` methods that call `newStore()` but does not say how to build a store. `InMemorySeatStoreTest` **extends** it and supplies only `newStore()`. JUnit runs every inherited `@Test` against the in-memory store. `JdbcSeatStoreIT` extends the same class and supplies a PostgreSQL store, so both adapters pass the identical tests. Surefire skips the abstract class itself.

### A test with threads

```java
@Test
void serveSweepsUntilTheShutdownSignalThenStopsCleanly() throws InterruptedException {
  var shutdown = new CountDownLatch(1);
  var env = Map.of("SWEEP_INTERVAL", "PT0.05S");
  var result = new Run[1];
  var server = new Thread(() -> result[0] = run(env, shutdown, "serve"));
  server.start();
  Thread.sleep(300);

  shutdown.countDown();
  server.join(10_000);

  assertThat(server.isAlive()).isFalse();
  assertThat(result[0].exitCode()).isZero();
  assertThat(result[0].out()).contains("serving:").contains("stopping:");
}
```

A test is ordinary Java, so it can start a thread: this one starts the `serve` command, waits 300 milliseconds, counts down the latch that stands for the shutdown signal, waits for the thread to end (`join` with a 10-second limit) and checks that it stopped and printed both lines.

## Try it yourself

1. `./mvnw -B test` runs every test; the totals appear at the end.
2. `./mvnw -B test -Dtest=SeatTransitionsTest` runs one class.
3. `./mvnw -B test -Dtest='SeatTransitionsTest#aFreeSeatCanBeHeldForTenMinutes'` runs one method (class `#` method).
4. In a scratch copy, break an expected value and run it: the failure report names the test and shows both values.
5. `grep -c "@Test" -r src/test | tail -3` counts the tests per file.

## Mistakes people make

- **Tests that depend on each other's order or leftovers.** JUnit creates a new instance per test; keep it that way and build state in `@BeforeEach`.
- **Vague names** like `test1`. A good name is a sentence about behavior.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Real time, real randomness, real network in tests.** This repository injects clocks and seeds so tests are repeatable.
- **One huge test** that checks ten things; the first failure hides the rest.

## Check yourself

1. *What does `@Test` do?* Marks a method as a test for JUnit to run.
2. *When does `@BeforeEach` run?* Before every test method.
3. *Why does each test get a new class instance?* So no test can see state left by another.
4. *What does `@TempDir` give a test?* A fresh temporary folder, deleted afterwards.
5. *What is the difference between a failure and an error?* A failure is a wrong assertion; an error is an unexpected exception.

## Further reading

- JUnit 6 User Guide: <https://docs.junit.org/6.0.3/overview.html> (annotations: <https://docs.junit.org/6.0.3/writing-tests/annotations.html>)
- JUnit project: <https://junit.org/>
