# Every test in this repository (a walkthrough with JUnit and AssertJ)

## What it is

This note is the complete tour of the test code. `junit.md` explains JUnit, `assertj.md` explains AssertJ, and this note applies them: it shows **every test class and every test method** in the repository, with its exact code, grouped by file. A **test** is a small program that checks one behavior and fails loudly if the behavior is wrong. A **test double** is a stand-in object used instead of a real one (a fake clock, a store that fails on purpose). A **fixture** is the shared setup a test needs. A **helper** is a small method that keeps tests short.

## Why this repository uses it

The tests are the lesson's requirements written as code: each method name is a sentence about behavior, so reading the names is reading the specification. The lesson's Done-when lines are proved by named tests, so a reader must be able to find and read each one.

## Where it appears

| Path | What it holds |
| --- | --- |
| `src/test/java/io/plainround/seatholds/` | 18 files: 85 unit tests, 14 database tests (`*IT`) and the architecture rules. The folders mirror the production packages. |
| `target/surefire-reports/` | The result of each run. |

## How it works underneath

**How to read a test.** Almost every test below has three parts, in this order:

1. **Arrange**: build the input (a cart, a calendar, a clock set to a known time).
2. **Act**: call the code being tested once.
3. **Assert**: check the result with `assertThat(...)` (`assertj.md`).

A test with no visible "act" line is a test that the act itself should fail: `assertThatThrownBy(() -> new TimeRange(...))` runs the code inside the `() ->` and expects an exception.

**Names.** A method name such as `aRangeThatEndsBeforeItStartsCannotBeBuilt` is a sentence. The note shows it as a sentence above the code ("A range that ends before it starts cannot be built.").

**Shared data.** `private static final` fields are constants shared by the tests of one class. `@BeforeEach` methods rebuild the state before every test (`junit.md`).

**Test doubles** appear where a real part would make a test slow, random or hard to provoke: a clock that stands still, a store whose writes fail, a stream that collects output instead of printing it.

**Test names and files.** `*Test` classes run in `make test` (Surefire, `maven-surefire.md`); `*IT` classes need Docker and run only in `make test-integration` (`maven-failsafe.md`). Files that are not tests (fixtures, helpers) are included because the tests need them.

## Walkthrough of this repository's use

Each file below starts with a plain description of what it tests and how its setup works, then shows the setup and helper code, then every test.

#### `application/HoldRaceTest.java` (4 tests)

The in-memory version of the lesson's race. `tenFreeSeats(label)` adds ten free seats to the store; `race(seats, customers)` runs `HoldRace` over a service with a fixed clock. Tests: 1,000 customers for 10 seats give exactly 10 winners and 990 losers in each of 20 runs; every seat ends held by a different customer; with fewer customers than seats everyone wins; the result prints as `wins=10 losses=990`. The same race runs against PostgreSQL in `JdbcHoldRaceIT`.

The top of the file:

```java
package io.plainround.seatholds.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.simulation.HoldRace;
import io.plainround.seatholds.application.simulation.RaceResult;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The race over the in-memory adapter; the JDBC adapter runs the same one in JdbcHoldRaceIT. */
class HoldRaceTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.application;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.application.simulation.HoldRace;` | `HoldRace`: a class of this repository, from the package `application.simulation`, that this test uses. |
| 6 | `import io.plainround.seatholds.application.simulation.RaceResult;` | `RaceResult`: a class of this repository, from the package `application.simulation`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 8 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 9 | `import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;` | `InMemorySeatStore`: a class of this repository, from the package `infrastructure.memory`, that this test uses. |
| 10 | `import io.plainround.seatholds.support.MutableClock;` | `MutableClock`: a class of this repository, from the package `support`, that this test uses. |
| 11 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 12 | `import java.util.List;` | `List`: an ordered collection (a numbered row of items). |
| 13 | `import java.util.stream.IntStream;` | `IntStream`: a stream (pipeline) of whole numbers, such as 0, 1, 2, ... for repeating a step many times. |
| 14 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 17 | `class HoldRaceTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |
| 16-16 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
private final InMemorySeatStore store = new InMemorySeatStore();

private List<SeatId> tenFreeSeats(String label) {
  List<SeatId> seats =
      IntStream.rangeClosed(1, 10).mapToObj(n -> new SeatId(label + "-s" + n)).toList();
  seats.forEach(store::add);
  return seats;
}

private RaceResult race(List<SeatId> seats, int customers) {
  var service = new SeatHoldService(store, new MutableClock(), Duration.ofMinutes(10));
  return new HoldRace(service).run(seats, customers);
}
```

**A thousand customers race for ten seats and exactly ten win in every one of twenty runs.**

```java
@Test
void aThousandCustomersRaceForTenSeatsAndExactlyTenWinInEveryOneOfTwentyRuns() {
  for (int run = 1; run <= 20; run++) {
    var result = race(tenFreeSeats("run" + run), 1_000);

    assertThat(result).as("run %d", run).isEqualTo(new RaceResult(10, 990));
  }
}
```

**Every seat ends held by exactly one customer and no customer holds two.**

```java
@Test
void everySeatEndsHeldByExactlyOneCustomerAndNoCustomerHoldsTwo() {
  var seats = tenFreeSeats("single");

  race(seats, 1_000);

  var holders = seats.stream().map(seat -> ((Held) store.get(seat)).customer()).toList();
  assertThat(holders).doesNotHaveDuplicates().hasSize(10);
}
```

**Fewer customers than seats all win.**

```java
@Test
void fewerCustomersThanSeatsAllWin() {
  assertThat(race(tenFreeSeats("few"), 4)).isEqualTo(new RaceResult(4, 0));
}
```

**The result prints the way the lesson shows it.**

```java
@Test
void theResultPrintsTheWayTheLessonShowsIt() {
  assertThat(new RaceResult(10, 990)).hasToString("wins=10 losses=990");
}
```

#### `application/SeatHoldServiceTest.java` (10 tests)

Tests the use cases with a movable clock and the in-memory store. `@BeforeEach` creates a store with one free seat `s-9` and a service with ten-minute holds. Tests: hold, a second customer refused, confirm within the time, confirm after ten minutes gives a reason and sells nothing, a sweep frees only expired holds, a freed seat can be held again, a sweep leaves sold seats alone, releasing one seat obeys the same rules, a lost write is read again and decided again, and a seat that keeps changing ends in a clear refusal after five attempts. The nested classes `LosesTheFirstWrite`, `LosesEveryWrite` and `ForwardingStore` are test doubles: stores that wrap the real one and misbehave on purpose (an intruder's hold lands between the read and the write; every write loses).

The top of the file:

```java
package io.plainround.seatholds.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeatHoldServiceTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.application;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.domain.CustomerId;` | `CustomerId`: a class of this repository, from the package `domain`, that this test uses. |
| 6 | `import io.plainround.seatholds.domain.Outcome.Reason;` | `Reason`: a class of this repository, from the package `domain.Outcome`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.Outcome.Success;` | `Success`: a class of this repository, from the package `domain.Outcome`, that this test uses. |
| 8 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 9 | `import io.plainround.seatholds.domain.SeatState;` | `SeatState`: a class of this repository, from the package `domain`, that this test uses. |
| 10 | `import io.plainround.seatholds.domain.SeatState.Free;` | `Free`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 11 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 12 | `import io.plainround.seatholds.domain.SeatState.Sold;` | `Sold`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 13 | `import io.plainround.seatholds.domain.SeatStore;` | `SeatStore`: a class of this repository, from the package `domain`, that this test uses. |
| 14 | `import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;` | `InMemorySeatStore`: a class of this repository, from the package `infrastructure.memory`, that this test uses. |
| 15 | `import io.plainround.seatholds.support.MutableClock;` | `MutableClock`: a class of this repository, from the package `support`, that this test uses. |
| 16 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 17 | `import java.util.List;` | `List`: an ordered collection (a numbered row of items). |
| 18 | `import java.util.concurrent.atomic.AtomicInteger;` | `AtomicInteger`: a whole number that several threads can add to safely, without losing updates. |
| 19 | `import org.junit.jupiter.api.BeforeEach;` | JUnit's `@BeforeEach` marker: the method runs before every single test, to rebuild fresh state. |
| 20 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 22 | `class SeatHoldServiceTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

Setup, shared data and helpers:

```java
private static final SeatId SEAT = new SeatId("s-9");

private static final CustomerId ADA = new CustomerId("ada");

private static final CustomerId BRUNO = new CustomerId("bruno");

private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

private final MutableClock clock = new MutableClock();

private SeatStore store;

private SeatHoldService service;

@BeforeEach
void oneFreeSeat() {
  store = new InMemorySeatStore();
  store.add(SEAT);
  service = new SeatHoldService(store, clock, TEN_MINUTES);
}

/** Another customer's hold lands between the service's read and its write, the first time. */
private static final class LosesTheFirstWrite extends ForwardingStore {
  private final SeatState intruder;
  private boolean intruded;

  LosesTheFirstWrite(SeatStore delegate, SeatState intruder) {
    super(delegate);
    this.intruder = intruder;
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    if (!intruded) {
      intruded = true;
      delegate.update(seat, expected, intruder);
    }
    return delegate.update(seat, expected, next);
  }
}

private static final class LosesEveryWrite extends ForwardingStore {
  private final AtomicInteger attempts = new AtomicInteger();

  LosesEveryWrite(SeatStore delegate) {
    super(delegate);
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    attempts.incrementAndGet();
    return false;
  }

  int attempts() {
    return attempts.get();
  }
}

private abstract static class ForwardingStore implements SeatStore {
  final SeatStore delegate;

  ForwardingStore(SeatStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public SeatState get(SeatId seat) {
    return delegate.get(seat);
  }

  @Override
  public boolean add(SeatId seat) {
    return delegate.add(seat);
  }

  @Override
  public List<SeatId> seats() {
    return delegate.seats();
  }
}
```

**Holding a free seat holds it for ten minutes.**

```java
@Test
void holdingAFreeSeatHoldsItForTenMinutes() {
  var outcome = service.hold(SEAT, ADA);

  assertThat(outcome).isEqualTo(new Success(new Held(ADA, MutableClock.START.plus(TEN_MINUTES))));
  assertThat(store.get(SEAT)).isEqualTo(new Held(ADA, MutableClock.START.plus(TEN_MINUTES)));
}
```

**A second customer is told the seat is held.**

```java
@Test
void aSecondCustomerIsToldTheSeatIsHeld() {
  service.hold(SEAT, ADA);

  assertThat(service.hold(SEAT, BRUNO)).isEqualTo(new Reason("seat is held"));
}
```

**Confirming within ten minutes sells the seat.**

```java
@Test
void confirmingWithinTenMinutesSellsTheSeat() {
  service.hold(SEAT, ADA);
  clock.advance(Duration.ofMinutes(9));

  assertThat(service.confirm(SEAT, ADA)).isEqualTo(new Success(new Sold(ADA)));
  assertThat(store.get(SEAT)).isEqualTo(new Sold(ADA));
}
```

**Confirming after ten minutes gives a reason and sells nothing.**

```java
@Test
void confirmingAfterTenMinutesGivesAReasonAndSellsNothing() {
  service.hold(SEAT, ADA);
  clock.advance(Duration.ofMinutes(11));

  assertThat(service.confirm(SEAT, ADA)).isEqualTo(new Reason("hold expired"));
  assertThat(store.get(SEAT)).isInstanceOf(Held.class);
}
```

**A sweep frees only the holds that have expired.**

```java
@Test
void aSweepFreesOnlyTheHoldsThatHaveExpired() {
  var second = new SeatId("s-10");
  store.add(second);
  service.hold(SEAT, ADA);
  clock.advance(Duration.ofMinutes(6));
  service.hold(second, BRUNO);
  clock.advance(Duration.ofMinutes(6));

  int released = service.sweep();

  assertThat(released).isEqualTo(1);
  assertThat(store.get(SEAT)).isEqualTo(new Free());
  assertThat(store.get(second)).isInstanceOf(Held.class);
}
```

**A seat freed by sweeping can be held again.**

```java
@Test
void aSeatFreedBySweepingCanBeHeldAgain() {
  service.hold(SEAT, ADA);
  clock.advance(Duration.ofMinutes(11));
  service.sweep();

  assertThat(service.hold(SEAT, BRUNO)).isInstanceOf(Success.class);
}
```

**A sweep leaves sold seats alone.**

```java
@Test
void aSweepLeavesSoldSeatsAlone() {
  service.hold(SEAT, ADA);
  service.confirm(SEAT, ADA);
  clock.advance(Duration.ofDays(1));

  assertThat(service.sweep()).isZero();
  assertThat(store.get(SEAT)).isEqualTo(new Sold(ADA));
}
```

**Releasing one seat decides by the same rules.**

```java
@Test
void releasingOneSeatDecidesByTheSameRules() {
  service.hold(SEAT, ADA);

  assertThat(service.release(SEAT)).isEqualTo(new Reason("hold not expired"));
  clock.advance(Duration.ofMinutes(11));
  assertThat(service.release(SEAT)).isEqualTo(new Success(new Free()));
}
```

**A lost write is read again and decided again.**

```java
@Test
void aLostWriteIsReadAgainAndDecidedAgain() {
  var interfering =
      new LosesTheFirstWrite(store, new Held(BRUNO, MutableClock.START.plus(TEN_MINUTES)));
  var racing = new SeatHoldService(interfering, clock, TEN_MINUTES);

  var outcome = racing.hold(SEAT, ADA);

  assertThat(outcome)
      .as("bruno got there between ada's read and write")
      .isEqualTo(new Reason("seat is held"));
  assertThat(store.get(SEAT)).isEqualTo(new Held(BRUNO, MutableClock.START.plus(TEN_MINUTES)));
}
```

**A seat that keeps changing under the caller ends in a clear refusal not a loop.**

```java
@Test
void aSeatThatKeepsChangingUnderTheCallerEndsInAClearRefusalNotALoop() {
  var alwaysLoses = new LosesEveryWrite(store);
  var racing = new SeatHoldService(alwaysLoses, clock, TEN_MINUTES);

  assertThat(racing.hold(SEAT, ADA)).isEqualTo(new Reason("seat is contended, try again"));
  assertThat(alwaysLoses.attempts()).isEqualTo(5);
}
```

#### `application/SweepConfirmRaceTest.java` (1 tests)

One test: 100 repetitions of the confirm-versus-sweep race on the in-memory store; each must end consistent (sold and confirmed, or freed and swept).

The top of the file:

```java
package io.plainround.seatholds.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.simulation.SweepConfirmRace;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import org.junit.jupiter.api.Test;

class SweepConfirmRaceTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.application;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.application.simulation.SweepConfirmRace;` | `SweepConfirmRace`: a class of this repository, from the package `application.simulation`, that this test uses. |
| 6 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 7 | `import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;` | `InMemorySeatStore`: a class of this repository, from the package `infrastructure.memory`, that this test uses. |
| 8 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 10 | `class SweepConfirmRaceTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

**Across one hundred repetitions a seat is never both sold and released.**

```java
@Test
void acrossOneHundredRepetitionsASeatIsNeverBothSoldAndReleased() {
  var race = new SweepConfirmRace(new InMemorySeatStore());

  for (int rep = 1; rep <= 100; rep++) {
    var result = race.run(new SeatId("rep-" + rep));

    assertThat(result.isConsistent())
        .as(
            "rep %d ended as %s, confirmed=%s, released=%d",
            rep, result.finalState(), result.confirmed(), result.released())
        .isTrue();
  }
}
```

#### `application/simulation/SimulationEdgeCasesTest.java` (8 tests)

Tests the race drivers themselves: how a repetition is judged consistent (two legal combinations and five illegal ones), that the rendezvous store forwards every call and gives up if the other party never reads, that a failure inside a customer's attempt is reported, that an interrupted race keeps the interrupt flag and says so, that a failing store makes the sweep race report "did not finish", and that a store failure keeps its cause. `FailingUpdates` is a store that refuses every write except the first hold.

The top of the file:

```java
package io.plainround.seatholds.application.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.application.simulation.SweepConfirmRace.Repetition;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatStoreException;
import io.plainround.seatholds.domain.UnknownSeatException;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The harness's own behavior: how it judges a repetition and how it fails. */
class SimulationEdgeCasesTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.application.simulation;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 4 | `import static org.assertj.core.api.Assertions.assertThatThrownBy;` | AssertJ's `assertThatThrownBy(() -> code)`: runs the code and checks that it throws, then lets the test inspect the exception. |
| 6 | `import io.plainround.seatholds.application.SeatHoldService;` | `SeatHoldService`: a class of this repository, from the package `application`, that this test uses. |
| 7 | `import io.plainround.seatholds.application.simulation.SweepConfirmRace.Repetition;` | `Repetition`: a class of this repository, from the package `application.simulation.SweepConfirmRace`, that this test uses. |
| 8 | `import io.plainround.seatholds.domain.CustomerId;` | `CustomerId`: a class of this repository, from the package `domain`, that this test uses. |
| 9 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 10 | `import io.plainround.seatholds.domain.SeatState;` | `SeatState`: a class of this repository, from the package `domain`, that this test uses. |
| 11 | `import io.plainround.seatholds.domain.SeatState.Free;` | `Free`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 12 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 13 | `import io.plainround.seatholds.domain.SeatState.Sold;` | `Sold`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 14 | `import io.plainround.seatholds.domain.SeatStore;` | `SeatStore`: a class of this repository, from the package `domain`, that this test uses. |
| 15 | `import io.plainround.seatholds.domain.SeatStoreException;` | `SeatStoreException`: a class of this repository, from the package `domain`, that this test uses. |
| 16 | `import io.plainround.seatholds.domain.UnknownSeatException;` | `UnknownSeatException`: a class of this repository, from the package `domain`, that this test uses. |
| 17 | `import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;` | `InMemorySeatStore`: a class of this repository, from the package `infrastructure.memory`, that this test uses. |
| 18 | `import io.plainround.seatholds.support.MutableClock;` | `MutableClock`: a class of this repository, from the package `support`, that this test uses. |
| 19 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 20 | `import java.time.Instant;` | `Instant`: one exact point on the timeline. |
| 21 | `import java.util.List;` | `List`: an ordered collection (a numbered row of items). |
| 22 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 25 | `class SimulationEdgeCasesTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |
| 24-24 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
private static final Instant SOME_TIME = Instant.parse("2026-01-01T00:10:00Z");

private static final CustomerId ADA = new CustomerId("ada");

/** A store whose writes fail, as a database that has gone away would. */
private static final class FailingUpdates implements SeatStore {
  private final SeatStore delegate;

  FailingUpdates(SeatStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public SeatState get(SeatId seat) {
    return delegate.get(seat);
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    if (next instanceof Held) {
      return delegate.update(seat, expected, next);
    }
    throw new SeatStoreException("database is down", null);
  }

  @Override
  public boolean add(SeatId seat) {
    return delegate.add(seat);
  }

  @Override
  public List<SeatId> seats() {
    return delegate.seats();
  }
}
```

**A repetition is consistent only when the seat and both reports agree.**

```java
@Test
void aRepetitionIsConsistentOnlyWhenTheSeatAndBothReportsAgree() {
  assertThat(new Repetition(new Sold(ADA), true, 0).isConsistent()).isTrue();
  assertThat(new Repetition(new Free(), false, 1).isConsistent()).isTrue();

  assertThat(new Repetition(new Sold(ADA), true, 1).isConsistent())
      .as("sold and released")
      .isFalse();
  assertThat(new Repetition(new Sold(ADA), false, 0).isConsistent())
      .as("sold, not confirmed")
      .isFalse();
  assertThat(new Repetition(new Free(), true, 1).isConsistent())
      .as("freed and confirmed")
      .isFalse();
  assertThat(new Repetition(new Free(), false, 0).isConsistent()).as("nobody won").isFalse();
  assertThat(new Repetition(new Held(ADA, SOME_TIME), false, 0).isConsistent())
      .as("a third state")
      .isFalse();
}
```

**The rendezvous store forwards every operation.**

```java
@Test
void theRendezvousStoreForwardsEveryOperation() {
  var inner = new InMemorySeatStore();
  var store = new RendezvousSeatStore(inner, 1);
  var seat = new SeatId("s-1");

  assertThat(store.add(seat)).isTrue();
  assertThat(store.seats()).containsExactly(seat);
  assertThat(store.get(seat)).isEqualTo(new Free());
  assertThat(store.update(seat, new Free(), new Sold(ADA))).isTrue();
  assertThat(inner.get(seat)).isEqualTo(new Sold(ADA));
}
```

**The rendezvous store gives up when the other participant never reads.**

```java
@Test
void theRendezvousStoreGivesUpWhenTheOtherParticipantNeverReads() {
  var inner = new InMemorySeatStore();
  inner.add(new SeatId("s-1"));
  var store = new RendezvousSeatStore(inner, 2, Duration.ofMillis(50));

  assertThatThrownBy(() -> store.get(new SeatId("s-1")))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("never read");
}
```

**A race over a seat the store does not know reports the customers failure.**

```java
@Test
void aRaceOverASeatTheStoreDoesNotKnowReportsTheCustomersFailure() {
  var service =
      new SeatHoldService(new InMemorySeatStore(), new MutableClock(), Duration.ofMinutes(10));

  assertThatThrownBy(() -> new HoldRace(service).run(List.of(new SeatId("missing")), 3))
      .isInstanceOf(IllegalStateException.class)
      .hasCauseInstanceOf(UnknownSeatException.class);
}
```

**An interrupted race keeps the interrupt flag and says so.**

```java
@Test
void anInterruptedRaceKeepsTheInterruptFlagAndSaysSo() {
  var store = new InMemorySeatStore();
  var seat = new SeatId("s-1");
  store.add(seat);
  var service = new SeatHoldService(store, new MutableClock(), Duration.ofMinutes(10));

  Thread.currentThread().interrupt();
  try {
    assertThatThrownBy(() -> new HoldRace(service).run(List.of(seat), 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("race interrupted");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  } finally {
    Thread.interrupted();
  }
}
```

**A sweep race over a store that fails reports that the race did not finish.**

```java
@Test
void aSweepRaceOverAStoreThatFailsReportsThatTheRaceDidNotFinish() {
  SeatStore broken = new FailingUpdates(new InMemorySeatStore());

  assertThatThrownBy(() -> new SweepConfirmRace(broken).run(new SeatId("s-1")))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("did not finish");
}
```

**A sweep race that is interrupted keeps the interrupt flag and says so.**

```java
@Test
void aSweepRaceThatIsInterruptedKeepsTheInterruptFlagAndSaysSo() {
  var race = new SweepConfirmRace(new InMemorySeatStore());

  Thread.currentThread().interrupt();
  try {
    assertThatThrownBy(() -> race.run(new SeatId("s-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("race interrupted");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  } finally {
    Thread.interrupted();
  }
}
```

**A store failure keeps its cause.**

```java
@Test
void aStoreFailureKeepsItsCause() {
  var cause = new IllegalStateException("connection refused");

  var failure = new SeatStoreException("reading seat s-1", cause);

  assertThat(failure).hasMessage("reading seat s-1").hasCause(cause);
}
```

#### `architecture/ArchitectureTest.java`

The seven ArchUnit rules: the layers, the domain uses only the JDK, the application uses only the domain and the JDK, no printing or direct `java.util.logging` outside the command-line layer, no `java.util.concurrent` in the domain, and SQL, Hikari and PostgreSQL types only in the JDBC adapter. Quoted in full and explained in `archunit.md`.

Quoted in full in `archunit.md`.

#### `domain/SeatTransitionsTest.java` (14 tests)

Tests the pure transition functions with a movable clock (`MutableClock`). `heldByAdaUntilTenMinutesFromNow()` builds a hold. Tests cover hold on a free, held and sold seat; confirm by the holder before the expiry, after it (a reason, never a sale), exactly at the expiry (still succeeds) and one millisecond later (fails), by someone else, on a free seat and on a sold seat; release of an expired hold, never of an unexpired one even at the exact expiry, never of a free or sold seat; and a loop over five instants around the boundary that proves exactly one of confirm and release is allowed at any instant.

The top of the file:

```java
package io.plainround.seatholds.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeatTransitionsTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.domain;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.domain.Outcome.Reason;` | `Reason`: a class of this repository, from the package `domain.Outcome`, that this test uses. |
| 6 | `import io.plainround.seatholds.domain.Outcome.Success;` | `Success`: a class of this repository, from the package `domain.Outcome`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.SeatState.Free;` | `Free`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 8 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 9 | `import io.plainround.seatholds.domain.SeatState.Sold;` | `Sold`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 10 | `import io.plainround.seatholds.support.MutableClock;` | `MutableClock`: a class of this repository, from the package `support`, that this test uses. |
| 11 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 12 | `import java.time.Instant;` | `Instant`: one exact point on the timeline. |
| 13 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 15 | `class SeatTransitionsTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

Setup, shared data and helpers:

```java
private static final CustomerId ADA = new CustomerId("ada");

private static final CustomerId BRUNO = new CustomerId("bruno");

private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

private final MutableClock clock = new MutableClock();

private Held heldByAdaUntilTenMinutesFromNow() {
  return new Held(ADA, clock.now().plus(TEN_MINUTES));
}
```

**A free seat can be held for ten minutes.**

```java
@Test
void aFreeSeatCanBeHeldForTenMinutes() {
  var outcome = SeatTransitions.hold(new Free(), ADA, clock, TEN_MINUTES);

  assertThat(outcome).isEqualTo(new Success(new Held(ADA, MutableClock.START.plus(TEN_MINUTES))));
}
```

**A held seat cannot be held again.**

```java
@Test
void aHeldSeatCannotBeHeldAgain() {
  var outcome =
      SeatTransitions.hold(heldByAdaUntilTenMinutesFromNow(), BRUNO, clock, TEN_MINUTES);

  assertThat(outcome).isEqualTo(new Reason("seat is held"));
}
```

**A sold seat cannot be held.**

```java
@Test
void aSoldSeatCannotBeHeld() {
  assertThat(SeatTransitions.hold(new Sold(ADA), BRUNO, clock, TEN_MINUTES))
      .isEqualTo(new Reason("already sold"));
}
```

**The holder confirming before the expiry sells the seat.**

```java
@Test
void theHolderConfirmingBeforeTheExpirySellsTheSeat() {
  var outcome = SeatTransitions.confirm(heldByAdaUntilTenMinutesFromNow(), ADA, clock);

  assertThat(outcome).isEqualTo(new Success(new Sold(ADA)));
}
```

**Confirming an expired hold gives a reason not a sale.**

```java
@Test
void confirmingAnExpiredHoldGivesAReasonNotASale() {
  var expiredHold = new Held(ADA, clock.now().minus(Duration.ofMinutes(1)));

  var outcome = SeatTransitions.confirm(expiredHold, ADA, clock);

  assertThat(outcome).isEqualTo(new Reason("hold expired"));
}
```

**A confirm at the exact expiry instant still succeeds.**

```java
@Test
void aConfirmAtTheExactExpiryInstantStillSucceeds() {
  var hold = new Held(ADA, clock.now());

  assertThat(SeatTransitions.confirm(hold, ADA, clock)).isEqualTo(new Success(new Sold(ADA)));
}
```

**A confirm one millisecond after the expiry fails.**

```java
@Test
void aConfirmOneMillisecondAfterTheExpiryFails() {
  var hold = new Held(ADA, clock.now());
  clock.advance(Duration.ofMillis(1));

  assertThat(SeatTransitions.confirm(hold, ADA, clock)).isEqualTo(new Reason("hold expired"));
}
```

**Someone else cannot confirm the hold.**

```java
@Test
void someoneElseCannotConfirmTheHold() {
  assertThat(SeatTransitions.confirm(heldByAdaUntilTenMinutesFromNow(), BRUNO, clock))
      .isEqualTo(new Reason("held by someone else"));
}
```

**An expired hold cannot be confirmed by someone else either.**

```java
@Test
void anExpiredHoldCannotBeConfirmedBySomeoneElseEither() {
  var expired = new Held(ADA, clock.now().minusSeconds(5));

  assertThat(SeatTransitions.confirm(expired, BRUNO, clock))
      .isEqualTo(new Reason("held by someone else"));
}
```

**A free seat cannot be confirmed and a sold seat cannot be confirmed again.**

```java
@Test
void aFreeSeatCannotBeConfirmedAndASoldSeatCannotBeConfirmedAgain() {
  assertThat(SeatTransitions.confirm(new Free(), ADA, clock)).isEqualTo(new Reason("not held"));
  assertThat(SeatTransitions.confirm(new Sold(ADA), ADA, clock))
      .isEqualTo(new Reason("already sold"));
}
```

**An expired hold can be released.**

```java
@Test
void anExpiredHoldCanBeReleased() {
  var expired = new Held(ADA, clock.now().minusSeconds(1));

  assertThat(SeatTransitions.release(expired, clock)).isEqualTo(new Success(new Free()));
}
```

**A hold that has not expired cannot be released not even at the exact expiry instant.**

```java
@Test
void aHoldThatHasNotExpiredCannotBeReleasedNotEvenAtTheExactExpiryInstant() {
  assertThat(SeatTransitions.release(new Held(ADA, clock.now()), clock))
      .isEqualTo(new Reason("hold not expired"));
  assertThat(SeatTransitions.release(heldByAdaUntilTenMinutesFromNow(), clock))
      .isEqualTo(new Reason("hold not expired"));
}
```

**A free or sold seat cannot be released.**

```java
@Test
void aFreeOrSoldSeatCannotBeReleased() {
  assertThat(SeatTransitions.release(new Free(), clock)).isEqualTo(new Reason("not held"));
  assertThat(SeatTransitions.release(new Sold(ADA), clock)).isEqualTo(new Reason("already sold"));
}
```

**Confirm and release never both succeed for one state at one instant.**

```java
@Test
void confirmAndReleaseNeverBothSucceedForOneStateAtOneInstant() {
  for (long offsetMillis = -2; offsetMillis <= 2; offsetMillis++) {
    var hold = new Held(ADA, Instant.parse("2026-01-01T00:10:00Z"));
    long offset = offsetMillis;
    var at = (Clock) () -> hold.expiresAt().plusMillis(offset);

    boolean confirms = SeatTransitions.confirm(hold, ADA, at) instanceof Success;
    boolean releases = SeatTransitions.release(hold, at) instanceof Success;

    assertThat(confirms ^ releases).as("offset %d ms", offsetMillis).isTrue();
  }
}
```

#### `domain/ValueObjectsTest.java` (5 tests)

Tests the small types: ids are never blank and are trimmed; a hold needs a customer and an expiry; a sale needs a customer; an expiry is kept to the millisecond; `SeatState` and `Outcome` are sealed with exactly the expected permitted subclasses (checked with reflection: `getPermittedSubclasses()`).

The top of the file:

```java
package io.plainround.seatholds.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ValueObjectsTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.domain;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 4 | `import static org.assertj.core.api.Assertions.assertThatThrownBy;` | AssertJ's `assertThatThrownBy(() -> code)`: runs the code and checks that it throws, then lets the test inspect the exception. |
| 6 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.SeatState.Sold;` | `Sold`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 8 | `import java.time.Instant;` | `Instant`: one exact point on the timeline. |
| 9 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 11 | `class ValueObjectsTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

**Ids are never blank and are trimmed.**

```java
@Test
void idsAreNeverBlankAndAreTrimmed() {
  assertThat(new SeatId(" s-9 ")).isEqualTo(new SeatId("s-9")).hasToString("s-9");
  assertThat(new CustomerId(" ada ")).isEqualTo(new CustomerId("ada")).hasToString("ada");
  assertThatThrownBy(() -> new SeatId(" ")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> new SeatId(null)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> new CustomerId("")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> new CustomerId(null)).isInstanceOf(IllegalArgumentException.class);
}
```

**A hold needs a customer and an expiry.**

```java
@Test
void aHoldNeedsACustomerAndAnExpiry() {
  assertThatThrownBy(() -> new Held(null, Instant.EPOCH))
      .isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> new Held(new CustomerId("ada"), null))
      .isInstanceOf(IllegalArgumentException.class);
}
```

**A sale needs a customer.**

```java
@Test
void aSaleNeedsACustomer() {
  assertThatThrownBy(() -> new Sold(null)).isInstanceOf(IllegalArgumentException.class);
}
```

**An expiry is kept to the millisecond so every store compares it the same.**

```java
@Test
void anExpiryIsKeptToTheMillisecondSoEveryStoreComparesItTheSame() {
  var precise = new Held(new CustomerId("ada"), Instant.parse("2026-01-01T00:10:00.123456789Z"));

  assertThat(precise.expiresAt()).isEqualTo(Instant.parse("2026-01-01T00:10:00.123Z"));
}
```

**The seat state is sealed with exactly three kinds.**

```java
@Test
void theSeatStateIsSealedWithExactlyThreeKinds() {
  assertThat(SeatState.class.getPermittedSubclasses())
      .containsExactlyInAnyOrder(SeatState.Free.class, Held.class, Sold.class);
  assertThat(Outcome.class.getPermittedSubclasses())
      .containsExactlyInAnyOrder(Outcome.Success.class, Outcome.Reason.class);
}
```

#### `e2e/ScenarioEndToEndTest.java` (5 tests)

Runs the `scenario` command once (`@BeforeAll`) on the in-memory store and checks the output: it starts with the expired-confirm line, the stale update is rejected, there are exactly five `wins=10 losses=990` lines, the sweep race reports `inconsistent=0`, and the plain-write store sells the seat twice. The command's exit code must be 0, meaning every invariant held.

The top of the file:

```java
package io.plainround.seatholds.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.interfaces.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The lesson's scenarios through the command a reader runs with {@code make run}. */
class ScenarioEndToEndTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.e2e;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.interfaces.cli.Cli;` | `Cli`: a class of this repository, from the package `interfaces.cli`, that this test uses. |
| 6 | `import java.io.ByteArrayOutputStream;` | `ByteArrayOutputStream`: an output channel that collects what is written into memory, so a test can read it back. |
| 7 | `import java.io.PrintStream;` | `PrintStream`: an output channel that can print text (what `System.out` is). |
| 8 | `import java.nio.charset.StandardCharsets;` | `StandardCharsets`: names of text encodings such as UTF-8 (how characters become bytes). |
| 9 | `import java.util.Map;` | `Map`: a collection of key-to-value pairs (like a dictionary). |
| 10 | `import java.util.concurrent.CountDownLatch;` | `CountDownLatch`: a counter that threads wait on; it releases all waiting threads at once when it reaches zero. Used to start many threads at the same instant. |
| 11 | `import org.junit.jupiter.api.BeforeAll;` | JUnit's `@BeforeAll` marker: the (static) method runs once before all the tests of the class. |
| 12 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 15 | `class ScenarioEndToEndTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |
| 14-14 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
private static String output;

@BeforeAll
static void runTheScenario() {
  var out = new ByteArrayOutputStream();
  int exitCode =
      new Cli(
              Map.of(),
              new PrintStream(out, true, StandardCharsets.UTF_8),
              new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
              new CountDownLatch(0))
          .run("scenario");
  assertThat(exitCode).as("every invariant held").isZero();
  output = out.toString(StandardCharsets.UTF_8);
}
```

**Confirming an expired hold gives a reason never a success.**

```java
@Test
void confirmingAnExpiredHoldGivesAReasonNeverASuccess() {
  assertThat(output)
      .startsWith("confirm expired hold -> Reason[message=hold expired]  (never Success)\n");
}
```

**The second update with a stale expected state is rejected.**

```java
@Test
void theSecondUpdateWithAStaleExpectedStateIsRejected() {
  assertThat(output).contains("first update: true  second update (stale expected): false\n");
}
```

**Every one of five races has exactly ten winners and nine hundred ninety rejections.**

```java
@Test
void everyOneOfFiveRacesHasExactlyTenWinnersAndNineHundredNinetyRejections() {
  assertThat(output.lines().filter(line -> line.equals("race result: wins=10 losses=990")))
      .hasSize(5);
}
```

**The sweep versus confirm race never ends inconsistent.**

```java
@Test
void theSweepVersusConfirmRaceNeverEndsInconsistent() {
  assertThat(output).contains("inconsistent=0");
  assertThat(output).contains("never both, never a third state");
}
```

**A plain write instead of compare and set sells the seat twice.**

```java
@Test
void aPlainWriteInsteadOfCompareAndSetSellsTheSeatTwice() {
  assertThat(output)
      .contains(
          "first update: true  second update (stale expected): true  <- both were told they won seat s-9");
}
```

#### `infrastructure/InMemorySeatStoreTest.java`

Twelve lines: it extends `SeatStoreContract` and supplies only `newStore()`, so all ten contract tests run against the in-memory store.

The top of the file:

```java
package io.plainround.seatholds.infrastructure;

import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;

class InMemorySeatStoreTest extends SeatStoreContract {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.infrastructure;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import io.plainround.seatholds.domain.SeatStore;` | `SeatStore`: a class of this repository, from the package `domain`, that this test uses. |
| 4 | `import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;` | `InMemorySeatStore`: a class of this repository, from the package `infrastructure.memory`, that this test uses. |
| 6 | `class InMemorySeatStoreTest extends SeatStoreContract` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. |

Setup, shared data and helpers:

```java
@Override
protected SeatStore newStore() {
  return new InMemorySeatStore();
}
```

#### `infrastructure/PlainWriteSeatStoreTest.java` (3 tests)

The counter-example store: a stale write succeeds, so two callers both think they won; the domain decides correctly both times and the seat is still lost because only the store could have stopped it; unknown seats are still errors.

The top of the file:

```java
package io.plainround.seatholds.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatTransitions;
import io.plainround.seatholds.domain.UnknownSeatException;
import io.plainround.seatholds.infrastructure.memory.PlainWriteSeatStore;
import io.plainround.seatholds.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The counter-example: what the store contract exists to catch. */
class PlainWriteSeatStoreTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.infrastructure;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 4 | `import static org.assertj.core.api.Assertions.assertThatThrownBy;` | AssertJ's `assertThatThrownBy(() -> code)`: runs the code and checks that it throws, then lets the test inspect the exception. |
| 6 | `import io.plainround.seatholds.domain.CustomerId;` | `CustomerId`: a class of this repository, from the package `domain`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.Outcome.Success;` | `Success`: a class of this repository, from the package `domain.Outcome`, that this test uses. |
| 8 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 9 | `import io.plainround.seatholds.domain.SeatState.Free;` | `Free`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 10 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 11 | `import io.plainround.seatholds.domain.SeatTransitions;` | `SeatTransitions`: a class of this repository, from the package `domain`, that this test uses. |
| 12 | `import io.plainround.seatholds.domain.UnknownSeatException;` | `UnknownSeatException`: a class of this repository, from the package `domain`, that this test uses. |
| 13 | `import io.plainround.seatholds.infrastructure.memory.PlainWriteSeatStore;` | `PlainWriteSeatStore`: a class of this repository, from the package `infrastructure.memory`, that this test uses. |
| 14 | `import io.plainround.seatholds.support.MutableClock;` | `MutableClock`: a class of this repository, from the package `support`, that this test uses. |
| 15 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 16 | `import java.time.Instant;` | `Instant`: one exact point on the timeline. |
| 17 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 20 | `class PlainWriteSeatStoreTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |
| 19-19 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
private static final Instant EXPIRY = Instant.parse("2026-01-01T00:10:00Z");
```

**A stale write succeeds so two callers both think they won the seat.**

```java
@Test
void aStaleWriteSucceedsSoTwoCallersBothThinkTheyWonTheSeat() {
  var store = new PlainWriteSeatStore();
  var seat = new SeatId("s-9");
  store.add(seat);
  var adaRead = store.get(seat);
  var brunoRead = store.get(seat);

  var ada = store.update(seat, adaRead, new Held(new CustomerId("ada"), EXPIRY));
  var bruno = store.update(seat, brunoRead, new Held(new CustomerId("bruno"), EXPIRY));

  assertThat(ada).isTrue();
  assertThat(bruno).as("the stale write that a compare-and-set refuses").isTrue();
  assertThat(store.get(seat)).isEqualTo(new Held(new CustomerId("bruno"), EXPIRY));
}
```

**The domain decides right both times and still the seat is lost because only the store could stop it.**

```java
@Test
void theDomainDecidesRightBothTimesAndStillTheSeatIsLostBecauseOnlyTheStoreCouldStopIt() {
  var store = new PlainWriteSeatStore();
  var seat = new SeatId("s-1");
  store.add(seat);
  var clock = new MutableClock();
  var tenMinutes = Duration.ofMinutes(10);
  var adaSaw = store.get(seat);
  var brunoSaw = store.get(seat);

  var adaDecision = SeatTransitions.hold(adaSaw, new CustomerId("ada"), clock, tenMinutes);
  var brunoDecision = SeatTransitions.hold(brunoSaw, new CustomerId("bruno"), clock, tenMinutes);

  assertThat(adaDecision).isInstanceOf(Success.class);
  assertThat(brunoDecision).isInstanceOf(Success.class);
  assertThat(store.update(seat, adaSaw, ((Success) adaDecision).newState())).isTrue();
  assertThat(store.update(seat, brunoSaw, ((Success) brunoDecision).newState())).isTrue();
}
```

**An unknown seat is still an error.**

```java
@Test
void anUnknownSeatIsStillAnError() {
  var store = new PlainWriteSeatStore();

  assertThatThrownBy(() -> store.get(new SeatId("x"))).isInstanceOf(UnknownSeatException.class);
  assertThatThrownBy(() -> store.update(new SeatId("x"), new Free(), new Free()))
      .isInstanceOf(UnknownSeatException.class);
  assertThat(store.add(new SeatId("x"))).isTrue();
  assertThat(store.add(new SeatId("x"))).isFalse();
  assertThat(store.seats()).containsExactly(new SeatId("x"));
}
```

#### `infrastructure/ScheduledSweeperTest.java` (2 tests)

Tests the sweeper with a fake `ExpireHolds` that counts sweeps: it sweeps repeatedly (three sweeps within ten seconds), stops sweeping when closed, and keeps sweeping after a sweep throws. `CountDownLatch.await(10, SECONDS)` waits at most ten seconds for the third sweep.

The top of the file:

```java
package io.plainround.seatholds.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.ExpireHolds;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.infrastructure.scheduling.ScheduledSweeper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScheduledSweeperTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.infrastructure;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.application.ExpireHolds;` | `ExpireHolds`: a class of this repository, from the package `application`, that this test uses. |
| 6 | `import io.plainround.seatholds.domain.Outcome;` | `Outcome`: a class of this repository, from the package `domain`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 8 | `import io.plainround.seatholds.infrastructure.scheduling.ScheduledSweeper;` | `ScheduledSweeper`: a class of this repository, from the package `infrastructure.scheduling`, that this test uses. |
| 9 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 10 | `import java.util.concurrent.CountDownLatch;` | `CountDownLatch`: a counter that threads wait on; it releases all waiting threads at once when it reaches zero. Used to start many threads at the same instant. |
| 11 | `import java.util.concurrent.TimeUnit;` | `TimeUnit`: a unit (seconds, milliseconds) for waiting and timeouts. |
| 12 | `import java.util.concurrent.atomic.AtomicInteger;` | `AtomicInteger`: a whole number that several threads can add to safely, without losing updates. |
| 13 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 15 | `class ScheduledSweeperTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

Setup, shared data and helpers:

```java
private static final class CountingSweeps implements ExpireHolds {
  final AtomicInteger sweeps = new AtomicInteger();
  final CountDownLatch threeSweeps = new CountDownLatch(3);
  final boolean failEverySweep;

  CountingSweeps(boolean failEverySweep) {
    this.failEverySweep = failEverySweep;
  }

  @Override
  public int sweep() {
    sweeps.incrementAndGet();
    threeSweeps.countDown();
    if (failEverySweep) {
      throw new IllegalStateException("store down");
    }
    return 0;
  }

  @Override
  public Outcome release(SeatId seat) {
    throw new UnsupportedOperationException();
  }
}
```

**It sweeps repeatedly and stops when closed.**

```java
@Test
void itSweepsRepeatedlyAndStopsWhenClosed() throws InterruptedException {
  var holds = new CountingSweeps(false);

  try (var sweeper = new ScheduledSweeper(holds, Duration.ofMillis(10))) {
    assertThat(holds.threeSweeps.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(sweeper).isNotNull();
  }

  int atClose = holds.sweeps.get();
  Thread.sleep(100);
  assertThat(holds.sweeps.get()).as("no sweep after close").isEqualTo(atClose);
}
```

**A sweep that throws does not stop the next one.**

```java
@Test
void aSweepThatThrowsDoesNotStopTheNextOne() throws InterruptedException {
  var holds = new CountingSweeps(true);

  try (var sweeper = new ScheduledSweeper(holds, Duration.ofMillis(10))) {
    assertThat(holds.threeSweeps.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(sweeper).isNotNull();
  }
}
```

#### `infrastructure/SeatStoreContract.java` (10 tests)

The abstract contract every `SeatStore` must pass (`junit.md`). `newStore()` is the one thing a subclass supplies. `@BeforeEach` creates a store with one free seat. The tests: a new seat is free; get and update round-trip every kind of state; a held seat goes back to free; a stale expected state is rejected and changes nothing; an expected state of the right kind but wrong value is rejected; two concurrent updates of one seat give exactly one winner (25 rounds on virtual threads); an unknown seat is an error; adding a seat twice keeps its state; the seats are listed in id order; and a state read back equals the one written, even with nanoseconds.

The top of the file:

```java
package io.plainround.seatholds.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.UnknownSeatException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What any {@link SeatStore} must do, independent of how it is built. Each adapter's test class
 * extends this one and supplies a store; none of these tests changes between adapters, so passing
 * them is what makes an adapter trustworthy.
 */
public abstract class SeatStoreContract {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.infrastructure;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 4 | `import static org.assertj.core.api.Assertions.assertThatThrownBy;` | AssertJ's `assertThatThrownBy(() -> code)`: runs the code and checks that it throws, then lets the test inspect the exception. |
| 6 | `import io.plainround.seatholds.domain.CustomerId;` | `CustomerId`: a class of this repository, from the package `domain`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 8 | `import io.plainround.seatholds.domain.SeatState;` | `SeatState`: a class of this repository, from the package `domain`, that this test uses. |
| 9 | `import io.plainround.seatholds.domain.SeatState.Free;` | `Free`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 10 | `import io.plainround.seatholds.domain.SeatState.Held;` | `Held`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 11 | `import io.plainround.seatholds.domain.SeatState.Sold;` | `Sold`: a class of this repository, from the package `domain.SeatState`, that this test uses. |
| 12 | `import io.plainround.seatholds.domain.SeatStore;` | `SeatStore`: a class of this repository, from the package `domain`, that this test uses. |
| 13 | `import io.plainround.seatholds.domain.UnknownSeatException;` | `UnknownSeatException`: a class of this repository, from the package `domain`, that this test uses. |
| 14 | `import java.time.Instant;` | `Instant`: one exact point on the timeline. |
| 15 | `import java.util.ArrayList;` | `ArrayList`: a list that grows as items are added. |
| 16 | `import java.util.Comparator;` | `Comparator`: a rule for putting items in order. |
| 17 | `import java.util.List;` | `List`: an ordered collection (a numbered row of items). |
| 18 | `import java.util.concurrent.CountDownLatch;` | `CountDownLatch`: a counter that threads wait on; it releases all waiting threads at once when it reaches zero. Used to start many threads at the same instant. |
| 19 | `import java.util.concurrent.ExecutorService;` | `ExecutorService`: a pool of threads that runs tasks you hand it. |
| 20 | `import java.util.concurrent.Executors;` | `Executors`: a factory that creates thread pools. |
| 21 | `import java.util.concurrent.Future;` | `Future`: a ticket for a result that a thread is still computing; `get()` waits for it. |
| 22 | `import java.util.concurrent.atomic.AtomicInteger;` | `AtomicInteger`: a whole number that several threads can add to safely, without losing updates. |
| 23 | `import org.junit.jupiter.api.BeforeEach;` | JUnit's `@BeforeEach` marker: the method runs before every single test, to rebuild fresh state. |
| 24 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 31 | `public abstract class SeatStoreContract` | The **class** that holds the code below. It is `public`, so any class can see it. It is `abstract`: it cannot be created by itself; other classes extend it and fill in the missing pieces. Every method marked `@Test` inside it is one test. |
| 26-30 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
private static final Instant EXPIRY = Instant.parse("2026-01-01T00:10:00Z");

private static final CustomerId ADA = new CustomerId("ada");

private static final CustomerId BRUNO = new CustomerId("bruno");

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

**A new seat is free.**

```java
@Test
void aNewSeatIsFree() {
  assertThat(store.get(seat)).isEqualTo(new Free());
}
```

**Get and update round trip every kind of state.**

```java
@Test
void getAndUpdateRoundTripEveryKindOfState() {
  var held = new Held(ADA, EXPIRY);

  assertThat(store.update(seat, new Free(), held)).isTrue();
  assertThat(store.get(seat)).isEqualTo(held);

  assertThat(store.update(seat, held, new Sold(ADA))).isTrue();
  assertThat(store.get(seat)).isEqualTo(new Sold(ADA));
}
```

**A held seat can go back to free.**

```java
@Test
void aHeldSeatCanGoBackToFree() {
  var held = new Held(ADA, EXPIRY);
  store.update(seat, new Free(), held);

  assertThat(store.update(seat, held, new Free())).isTrue();
  assertThat(store.get(seat)).isEqualTo(new Free());
}
```

**A stale expected state is rejected and nothing changes.**

```java
@Test
void aStaleExpectedStateIsRejectedAndNothingChanges() {
  var adaHold = new Held(ADA, EXPIRY);
  var first = store.update(seat, new Free(), adaHold);
  var second = store.update(seat, new Free(), new Held(BRUNO, EXPIRY));

  assertThat(first).isTrue();
  assertThat(second).isFalse();
  assertThat(store.get(seat)).isEqualTo(adaHold);
}
```

**An expected state of the right kind but the wrong value is rejected.**

```java
@Test
void anExpectedStateOfTheRightKindButTheWrongValueIsRejected() {
  store.update(seat, new Free(), new Held(ADA, EXPIRY));

  assertThat(store.update(seat, new Held(BRUNO, EXPIRY), new Sold(BRUNO))).isFalse();
  assertThat(store.update(seat, new Held(ADA, EXPIRY.plusSeconds(1)), new Sold(ADA))).isFalse();
}
```

**Two concurrent updates of one seat exactly one succeeds.**

```java
@Test
void twoConcurrentUpdatesOfOneSeatExactlyOneSucceeds() throws Exception {
  for (int round = 0; round < 25; round++) {
    var contested = new SeatId("contested-" + round + "-" + System.nanoTime());
    store.add(contested);
    var start = new CountDownLatch(1);
    var winners = new AtomicInteger();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> both = new ArrayList<>();
      for (CustomerId customer : List.of(ADA, BRUNO)) {
        both.add(
            pool.submit(
                () -> {
                  start.await();
                  if (store.update(contested, new Free(), new Held(customer, EXPIRY))) {
                    winners.incrementAndGet();
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> one : both) {
        one.get();
      }
    }
    assertThat(winners).as("round " + round).hasValue(1);
  }
}
```

**An unknown seat is an error.**

```java
@Test
void anUnknownSeatIsAnError() {
  var unknown = new SeatId("nowhere");

  assertThatThrownBy(() -> store.get(unknown)).isInstanceOf(UnknownSeatException.class);
  assertThatThrownBy(() -> store.update(unknown, new Free(), new Sold(ADA)))
      .isInstanceOf(UnknownSeatException.class);
}
```

**Adding a seat twice keeps its state.**

```java
@Test
void addingASeatTwiceKeepsItsState() {
  var held = new Held(ADA, EXPIRY);
  store.update(seat, new Free(), held);

  assertThat(store.add(seat)).isFalse();
  assertThat(store.get(seat)).isEqualTo(held);
}
```

**Seats are listed in id order.**

```java
@Test
void seatsAreListedInIdOrder() {
  String prefix = "order-" + System.nanoTime() + "-";
  store.add(new SeatId(prefix + "b"));
  store.add(new SeatId(prefix + "a"));
  store.add(new SeatId(prefix + "c"));

  var all = store.seats();

  assertThat(all)
      .as("the whole list is sorted, whatever else the store holds")
      .isSortedAccordingTo(Comparator.comparing(SeatId::value));
  assertThat(all.stream().map(SeatId::value).filter(id -> id.startsWith(prefix)))
      .containsExactly(prefix + "a", prefix + "b", prefix + "c");
}
```

**A state read back is equal to the one written.**

```java
@Test
void aStateReadBackIsEqualToTheOneWritten() {
  SeatState written = new Held(ADA, Instant.parse("2026-01-01T00:10:00.123456789Z"));
  store.update(seat, new Free(), written);

  assertThat(store.get(seat)).isEqualTo(written);
}
```

#### `infrastructure/jdbc/JdbcHoldRaceIT.java` (2 tests)

The lesson's two races against PostgreSQL: 20 races of 1,000 customers for 10 seats (each exactly 10 and 990) and 100 confirm-versus-sweep repetitions. A name ending in `IT` means Failsafe runs it, only with `-Pintegration` (`maven-failsafe.md`).

The top of the file:

```java
package io.plainround.seatholds.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.application.simulation.HoldRace;
import io.plainround.seatholds.application.simulation.RaceResult;
import io.plainround.seatholds.application.simulation.SweepConfirmRace;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.infrastructure.time.SystemClock;
import io.plainround.seatholds.support.PostgresFixture;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The lesson's two races against PostgreSQL: a thousand virtual threads, then confirm vs sweep. */
class JdbcHoldRaceIT {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.infrastructure.jdbc;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.application.SeatHoldService;` | `SeatHoldService`: a class of this repository, from the package `application`, that this test uses. |
| 6 | `import io.plainround.seatholds.application.simulation.HoldRace;` | `HoldRace`: a class of this repository, from the package `application.simulation`, that this test uses. |
| 7 | `import io.plainround.seatholds.application.simulation.RaceResult;` | `RaceResult`: a class of this repository, from the package `application.simulation`, that this test uses. |
| 8 | `import io.plainround.seatholds.application.simulation.SweepConfirmRace;` | `SweepConfirmRace`: a class of this repository, from the package `application.simulation`, that this test uses. |
| 9 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 10 | `import io.plainround.seatholds.infrastructure.time.SystemClock;` | `SystemClock`: a class of this repository, from the package `infrastructure.time`, that this test uses. |
| 11 | `import io.plainround.seatholds.support.PostgresFixture;` | `PostgresFixture`: a class of this repository, from the package `support`, that this test uses. |
| 12 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 13 | `import java.util.List;` | `List`: an ordered collection (a numbered row of items). |
| 14 | `import java.util.stream.IntStream;` | `IntStream`: a stream (pipeline) of whole numbers, such as 0, 1, 2, ... for repeating a step many times. |
| 15 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 18 | `class JdbcHoldRaceIT` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |
| 17-17 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
private final JdbcSeatStore store = new JdbcSeatStore(PostgresFixture.pool());
```

**A thousand customers race for ten seats and exactly ten win in every one of twenty runs.**

```java
@Test
void aThousandCustomersRaceForTenSeatsAndExactlyTenWinInEveryOneOfTwentyRuns() {
  var service = new SeatHoldService(store, new SystemClock(), Duration.ofMinutes(10));
  String batch = Long.toString(System.nanoTime(), 36);
  for (int run = 1; run <= 20; run++) {
    int number = run;
    List<SeatId> seats =
        IntStream.rangeClosed(1, 10)
            .mapToObj(n -> new SeatId("jdbc-" + batch + "-" + number + "-" + n))
            .toList();
    seats.forEach(store::add);

    var result = new HoldRace(service).run(seats, 1_000);

    assertThat(result).as("run %d", run).isEqualTo(new RaceResult(10, 990));
  }
}
```

**Across one hundred repetitions a seat is never both sold and released.**

```java
@Test
void acrossOneHundredRepetitionsASeatIsNeverBothSoldAndReleased() {
  var race = new SweepConfirmRace(store);
  String batch = Long.toString(System.nanoTime(), 36);

  for (int rep = 1; rep <= 100; rep++) {
    var result = race.run(new SeatId("sweep-" + batch + "-" + rep));

    assertThat(result.isConsistent())
        .as(
            "rep %d ended as %s, confirmed=%s, released=%d",
            rep, result.finalState(), result.confirmed(), result.released())
        .isTrue();
  }
}
```

#### `infrastructure/jdbc/JdbcSeatStoreIT.java` (2 tests)

Extends `SeatStoreContract` with a PostgreSQL store, so the identical ten contract tests run against the real database, and adds two database-only tests: the table's CHECK constraint refuses an impossible seat, and every change increments `version`.

The top of the file:

```java
package io.plainround.seatholds.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.SeatStoreContract;
import io.plainround.seatholds.support.PostgresFixture;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** The identical contract class the in-memory adapter passes, against PostgreSQL in a container. */
class JdbcSeatStoreIT extends SeatStoreContract {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.infrastructure.jdbc;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 4 | `import static org.assertj.core.api.Assertions.assertThatThrownBy;` | AssertJ's `assertThatThrownBy(() -> code)`: runs the code and checks that it throws, then lets the test inspect the exception. |
| 6 | `import io.plainround.seatholds.domain.SeatId;` | `SeatId`: a class of this repository, from the package `domain`, that this test uses. |
| 7 | `import io.plainround.seatholds.domain.SeatStore;` | `SeatStore`: a class of this repository, from the package `domain`, that this test uses. |
| 8 | `import io.plainround.seatholds.infrastructure.SeatStoreContract;` | `SeatStoreContract`: a class of this repository, from the package `infrastructure`, that this test uses. |
| 9 | `import io.plainround.seatholds.support.PostgresFixture;` | `PostgresFixture`: a class of this repository, from the package `support`, that this test uses. |
| 10 | `import java.sql.SQLException;` | `SQLException`: the error type for database failures. |
| 11 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 14 | `class JdbcSeatStoreIT extends SeatStoreContract` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |
| 13-13 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
@Override
protected SeatStore newStore() {
  return new JdbcSeatStore(PostgresFixture.pool());
}
```

**The database itself refuses an impossible seat.**

```java
@Test
void theDatabaseItselfRefusesAnImpossibleSeat() throws SQLException {
  new JdbcSeatStore(PostgresFixture.pool()).add(new SeatId("shape-check"));

  try (var connection = PostgresFixture.pool().getConnection();
      var statement = connection.createStatement()) {
    assertThatThrownBy(
            () ->
                statement.executeUpdate(
                    "UPDATE seats SET state_type = 'HELD' WHERE seat_id = 'shape-check'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("seats_state_shape");
  }
}
```

**Every change bumps the version column.**

```java
@Test
void everyChangeBumpsTheVersionColumn() throws SQLException {
  var store = new JdbcSeatStore(PostgresFixture.pool());
  var seat = new SeatId("version-check");
  store.add(seat);
  store.update(
      seat,
      new io.plainround.seatholds.domain.SeatState.Free(),
      new io.plainround.seatholds.domain.SeatState.Sold(
          new io.plainround.seatholds.domain.CustomerId("ada")));

  try (var connection = PostgresFixture.pool().getConnection();
      var statement = connection.createStatement();
      var rows =
          statement.executeQuery("SELECT version FROM seats WHERE seat_id = 'version-check'")) {
    assertThat(rows.next()).isTrue();
    assertThat(rows.getLong(1)).isEqualTo(1L);
  }
}
```

#### `interfaces/AppConfigTest.java` (4 tests)

Tests the settings reader: the defaults (memory store, ten-minute holds, 30-second sweep, ten seats), a JDBC configuration whose `toString` hides the password, bad values refused with the variable's name, and the JDBC store refused without a URL, a user or a valid pool and timeout.

The top of the file:

```java
package io.plainround.seatholds.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.interfaces.cli.AppConfig;
import io.plainround.seatholds.interfaces.cli.AppConfig.StoreKind;
import io.plainround.seatholds.interfaces.cli.ConfigurationException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.interfaces;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 4 | `import static org.assertj.core.api.Assertions.assertThatThrownBy;` | AssertJ's `assertThatThrownBy(() -> code)`: runs the code and checks that it throws, then lets the test inspect the exception. |
| 6 | `import io.plainround.seatholds.interfaces.cli.AppConfig;` | `AppConfig`: a class of this repository, from the package `interfaces.cli`, that this test uses. |
| 7 | `import io.plainround.seatholds.interfaces.cli.AppConfig.StoreKind;` | `StoreKind`: a class of this repository, from the package `interfaces.cli.AppConfig`, that this test uses. |
| 8 | `import io.plainround.seatholds.interfaces.cli.ConfigurationException;` | `ConfigurationException`: a class of this repository, from the package `interfaces.cli`, that this test uses. |
| 9 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 10 | `import java.util.Map;` | `Map`: a collection of key-to-value pairs (like a dictionary). |
| 11 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 13 | `class AppConfigTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

**Defaults are a memory store ten minute holds and ten seats.**

```java
@Test
void defaultsAreAMemoryStoreTenMinuteHoldsAndTenSeats() {
  var config = AppConfig.from(Map.of());

  assertThat(config.store()).isEqualTo(StoreKind.MEMORY);
  assertThat(config.holdFor()).isEqualTo(Duration.ofMinutes(10));
  assertThat(config.sweepEvery()).isEqualTo(Duration.ofSeconds(30));
  assertThat(config.seats()).isEqualTo(10);
  assertThat(config.database()).isNull();
}
```

**The jdbc store needs a database and its to string hides the password.**

```java
@Test
void theJdbcStoreNeedsADatabaseAndItsToStringHidesThePassword() {
  var config =
      AppConfig.from(
          Map.of(
              "STORE", "jdbc",
              "DATABASE_URL", "jdbc:postgresql://localhost:5432/seatholds",
              "DATABASE_USER", "seatholds",
              "DATABASE_PASSWORD", "hunter2",
              "POOL_SIZE", "4",
              "CONNECTION_TIMEOUT", "PT45S"));

  assertThat(config.store()).isEqualTo(StoreKind.JDBC);
  assertThat(config.database().poolSize()).isEqualTo(4);
  assertThat(config.database().connectionTimeout()).isEqualTo(Duration.ofSeconds(45));
  assertThat(config.database().toString()).doesNotContain("hunter2");
}
```

**Bad values are refused with the variable name.**

```java
@Test
void badValuesAreRefusedWithTheVariableName() {
  assertThatThrownBy(() -> AppConfig.from(Map.of("STORE", "mongo")))
      .isInstanceOf(ConfigurationException.class);
  assertThatThrownBy(() -> AppConfig.from(Map.of("HOLD_DURATION", "ten minutes")))
      .isInstanceOf(ConfigurationException.class);
  assertThatThrownBy(() -> AppConfig.from(Map.of("HOLD_DURATION", "PT0S")))
      .hasMessageContaining("HOLD_DURATION");
  assertThatThrownBy(() -> AppConfig.from(Map.of("SWEEP_INTERVAL", "-PT1S")))
      .hasMessageContaining("SWEEP_INTERVAL");
  assertThatThrownBy(() -> AppConfig.from(Map.of("SEATS", "0"))).hasMessageContaining("SEATS");
  assertThatThrownBy(() -> AppConfig.from(Map.of("SEATS", "ten")))
      .isInstanceOf(ConfigurationException.class);
  assertThatThrownBy(() -> AppConfig.from(Map.of("LOG_LEVEL", "LOUD")))
      .hasMessageContaining("LOG_LEVEL");
}
```

**The jdbc store without a url or with an empty pool is refused.**

```java
@Test
void theJdbcStoreWithoutAUrlOrWithAnEmptyPoolIsRefused() {
  assertThatThrownBy(() -> AppConfig.from(Map.of("STORE", "jdbc")))
      .hasMessageContaining("DATABASE_URL");
  assertThatThrownBy(
          () ->
              AppConfig.from(
                  Map.of(
                      "STORE", "jdbc",
                      "DATABASE_URL", "jdbc:postgresql://localhost/x",
                      "DATABASE_USER", "u",
                      "DATABASE_PASSWORD", "p",
                      "POOL_SIZE", "0")))
      .hasMessageContaining("POOL_SIZE");
  assertThatThrownBy(
          () ->
              AppConfig.from(
                  Map.of(
                      "STORE", "jdbc",
                      "DATABASE_URL", "jdbc:postgresql://localhost/x")))
      .hasMessageContaining("DATABASE_USER");
  assertThatThrownBy(
          () ->
              AppConfig.from(
                  Map.of(
                      "STORE", "jdbc",
                      "DATABASE_URL", "jdbc:postgresql://localhost/x",
                      "DATABASE_USER", "u",
                      "DATABASE_PASSWORD", "p",
                      "CONNECTION_TIMEOUT", "PT0.5S")))
      .hasMessageContaining("CONNECTION_TIMEOUT");
}
```

#### `interfaces/CliTest.java` (12 tests)

Tests the command-line driver. `run(env, shutdown, args)` builds a `Cli` with fake streams and a shutdown latch. Tests cover usage, unknown command, bad settings, `health`, `status`, `hold` and `confirm` (argument checks, success, refusal with exit 1, unknown seat), `sweep`, a small `race` (exactly 10 winners), and `serve`: it starts the command on a thread, waits 300 milliseconds, counts the latch down and checks that it stopped cleanly.

The top of the file:

```java
package io.plainround.seatholds.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.interfaces.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CliTest {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.interfaces;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import static org.assertj.core.api.Assertions.assertThat;` | AssertJ's `assertThat(actual)`: starts a readable check such as `assertThat(x).isEqualTo(y)`. `static` lets the test write `assertThat(...)` without the class name in front. |
| 5 | `import io.plainround.seatholds.interfaces.cli.Cli;` | `Cli`: a class of this repository, from the package `interfaces.cli`, that this test uses. |
| 6 | `import java.io.ByteArrayOutputStream;` | `ByteArrayOutputStream`: an output channel that collects what is written into memory, so a test can read it back. |
| 7 | `import java.io.PrintStream;` | `PrintStream`: an output channel that can print text (what `System.out` is). |
| 8 | `import java.nio.charset.StandardCharsets;` | `StandardCharsets`: names of text encodings such as UTF-8 (how characters become bytes). |
| 9 | `import java.util.Map;` | `Map`: a collection of key-to-value pairs (like a dictionary). |
| 10 | `import java.util.concurrent.CountDownLatch;` | `CountDownLatch`: a counter that threads wait on; it releases all waiting threads at once when it reaches zero. Used to start many threads at the same instant. |
| 11 | `import org.junit.jupiter.api.Test;` | JUnit's `@Test` marker: a method carrying it is a test that JUnit runs. |
| 13 | `class CliTest` | The **class** that holds the code below. It has no `public` modifier (package-private): JUnit runs such classes too, so none is needed. Every method marked `@Test` inside it is one test. |

Setup, shared data and helpers:

```java
private record Run(int exitCode, String out, String err) {}

private static Run run(Map<String, String> env, CountDownLatch shutdown, String... args) {
  var out = new ByteArrayOutputStream();
  var err = new ByteArrayOutputStream();
  int code =
      new Cli(
              env,
              new PrintStream(out, true, StandardCharsets.UTF_8),
              new PrintStream(err, true, StandardCharsets.UTF_8),
              shutdown)
          .run(args);
  return new Run(
      code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
}

private static Run run(String... args) {
  return run(Map.of(), new CountDownLatch(0), args);
}
```

**No arguments print the usage.**

```java
@Test
void noArgumentsPrintTheUsage() {
  var result = run();

  assertThat(result.exitCode()).isZero();
  assertThat(result.out()).contains("usage: seatholds");
}
```

**An unknown command exits with two.**

```java
@Test
void anUnknownCommandExitsWithTwo() {
  assertThat(run("dance").exitCode()).isEqualTo(2);
}
```

**Bad settings exit with two and say why.**

```java
@Test
void badSettingsExitWithTwoAndSayWhy() {
  var result = run(Map.of("SEATS", "0"), new CountDownLatch(0), "health");

  assertThat(result.exitCode()).isEqualTo(2);
  assertThat(result.err()).contains("SEATS");
}
```

**Health reports the store and the seats.**

```java
@Test
void healthReportsTheStoreAndTheSeats() {
  var result = run("health");

  assertThat(result.exitCode()).isZero();
  assertThat(result.out()).isEqualTo("ok store=memory seats=10\n");
}
```

**Status lists every seat as free at start up.**

```java
@Test
void statusListsEverySeatAsFreeAtStartUp() {
  var result = run(Map.of("SEATS", "3"), new CountDownLatch(0), "status");

  assertThat(result.out()).isEqualTo("s-1 Free[]\ns-2 Free[]\ns-3 Free[]\n");
}
```

**Hold and confirm need a seat and a customer.**

```java
@Test
void holdAndConfirmNeedASeatAndACustomer() {
  assertThat(run("hold", "s-1").exitCode()).isEqualTo(2);
  assertThat(run("confirm").exitCode()).isEqualTo(2);
}
```

**Holding a seat reports the new state.**

```java
@Test
void holdingASeatReportsTheNewState() {
  var result = run("hold", "s-1", "ada");

  assertThat(result.exitCode()).isZero();
  assertThat(result.out()).startsWith("Success[newState=Held[customer=ada, expiresAt=");
}
```

**Confirming a seat nobody held is a refusal with exit code one.**

```java
@Test
void confirmingASeatNobodyHeldIsARefusalWithExitCodeOne() {
  var result = run("confirm", "s-1", "ada");

  assertThat(result.exitCode()).isEqualTo(1);
  assertThat(result.out()).isEqualTo("Reason[message=not held]\n");
}
```

**An unknown seat is reported on standard error.**

```java
@Test
void anUnknownSeatIsReportedOnStandardError() {
  var result = run("hold", "s-99", "ada");

  assertThat(result.exitCode()).isEqualTo(1);
  assertThat(result.err()).contains("unknown seat: s-99");
}
```

**Sweep reports how many holds it released.**

```java
@Test
void sweepReportsHowManyHoldsItReleased() {
  assertThat(run("sweep").out()).isEqualTo("released 0 expired hold(s)\n");
}
```

**A small race prints one result per run.**

```java
@Test
void aSmallRacePrintsOneResultPerRun() {
  var result = run("race", "2", "50");

  assertThat(result.exitCode()).isZero();
  assertThat(result.out())
      .isEqualTo("race result: wins=10 losses=40\nrace result: wins=10 losses=40\n");
}
```

**Serve sweeps until the shutdown signal then stops cleanly.**

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

#### `support/MutableClock.java`

Not a test: a `Clock` a test can move by hand (`advance`, `set`), starting at a fixed instant. It keeps its time in an `AtomicReference` so threads can read it safely.

The top of the file:

```java
package io.plainround.seatholds.support;

import io.plainround.seatholds.domain.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test moves by hand. */
public final class MutableClock implements Clock {
```

| Line | Code | What it means |
| --- | --- | --- |
| 1 | `package io.plainround.seatholds.support;` | The **package** of this class: its folder path under `src/test/java`, written with dots. It matches the package of the code under test, so the test can use that code's non-public parts. |
| 3 | `import io.plainround.seatholds.domain.Clock;` | `Clock`: a class of this repository, from the package `domain`, that this test uses. |
| 4 | `import java.time.Duration;` | `Duration`: an amount of time (for example 30 seconds). |
| 5 | `import java.time.Instant;` | `Instant`: one exact point on the timeline. |
| 6 | `import java.util.concurrent.atomic.AtomicReference;` | `AtomicReference`: a box holding one object that several threads can read and replace safely. |
| 9 | `public final class MutableClock implements Clock` | The **class** that holds the code below. It is `public`, so any class can see it. |
| 8-8 | the comment | A comment (Javadoc): text for human readers; the compiler ignores it. |

Setup, shared data and helpers:

```java
/** The instant the lesson's examples start from. */
public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

private final AtomicReference<Instant> now = new AtomicReference<>(START);

@Override
public Instant now() {
  return now.get();
}

public void advance(Duration by) {
  now.updateAndGet(current -> current.plus(by));
}

public void set(Instant instant) {
  now.set(instant);
}
```

#### `support/PostgresFixture.java`

Not a test: the shared PostgreSQL for the integration tests. Quoted and explained in `testcontainers.md`.

Quoted in full in `testcontainers.md`.

## Try it yourself

1. `./mvnw -B test` runs all the tests above and prints one `Tests run:` line per class.
2. `./mvnw -B test -Dtest=SeatTransitionsTest` runs one class; open `target/surefire-reports/` to see its report.
3. Pick any test above, break the code under test in a scratch copy (for example change a `<` to `<=` in the class it tests), and run it: the failing test's name tells you which requirement broke.
4. `grep -c "@Test" -r src/test | grep -v ":0"` lists how many tests each file has.
5. `./mvnw -B test -Dtest='*Contract*,*Race*'` shows how a pattern selects several classes (the contract class is abstract and runs through its subclasses).

## Mistakes people make

- **Reading only the code under test.** The tests say what it must do; read both.
- **Skipping the helper section.** Shared data and helpers explain what `ADA`, `range(9, 0, 10, 0)` or `Carts.workedExample()` mean.
- **Changing a test to make it pass.** A failing test is a requirement being broken; fix the code, or change the requirement on purpose.
- **Treating doubles as the real thing.** A fake clock proves time-dependent logic; it does not prove the real clock works (that is one line).
- **Running only the unit tests when the change touches the database.** The `*IT` tests exist for that.

## Check yourself

1. *What are the three steps of most tests?* Arrange, act, assert.
2. *Why are test method names written as sentences?* So a failure reads as a broken requirement and the list of names reads as the specification.
3. *What is a test double?* A stand-in used instead of a real part, such as a fake clock.
4. *Which tests need Docker?* The `*IT` classes, run with `make test-integration`.
5. *How do you run a single test class?* `./mvnw -B test -Dtest=ClassName`.

## Further reading

- JUnit 6 User Guide, writing tests: <https://docs.junit.org/6.0.3/writing-tests/annotations.html>
- AssertJ Core 3.27.7: <https://assertj.github.io/doc/>
