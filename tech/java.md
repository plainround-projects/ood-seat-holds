# Java 21 (the language and the standard library)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`; the compiler (`javac.md`) turns them into bytecode and the Java Virtual Machine (JVM) runs it. Java is **statically typed**: every variable, parameter and return value has a type that the compiler checks before the program runs. This repository is written for **Java 21** (a long-term-support release) and tested on Java 25. The domain and the application layers use only the standard library; the database code uses three libraries (`jdbc.md`, `hikaricp.md`, `slf4j.md`).

## Why this repository uses it

The lesson designs a **state machine** for seats with a sealed type and pure functions, then races a thousand **virtual threads** against two storage adapters. Java 21 provides all of it: records and sealed interfaces for the states, pattern-matching `switch` with guards for the transitions, `ConcurrentHashMap` for an atomic compare-and-set, and virtual threads for the race (`java-concurrency.md`).

## Where it appears

| Path | What it does with Java |
| --- | --- |
| `src/main/java/io/plainround/seatholds/domain/` | `SeatState`, `Outcome`, `SeatTransitions` and the ports `SeatStore` and `Clock`. Uses only `java.*`, and not even `java.util.concurrent`. |
| `src/main/java/io/plainround/seatholds/application/` | The inbound ports `HoldSeats`, `ConfirmHold`, `ExpireHolds`, the service `SeatHoldService`, and the race drivers in `simulation/`. |
| `src/main/java/io/plainround/seatholds/infrastructure/` | Adapters: `memory/`, `jdbc/`, `scheduling/`, `time/`. |
| `src/main/java/io/plainround/seatholds/interfaces/cli/` | The command-line program. |
| `src/test/java/io/plainround/seatholds/` | The tests (`junit.md`). |

## How it works underneath

**Source to running program.** `javac` compiles each `.java` file to a `.class` file. The Makefile runs `java -cp "target/classes:target/lib/*" io.plainround.seatholds.interfaces.cli.Main scenario`: a JVM starts, the **class path** (`-cp`) tells it where compiled code and library jars are, it loads `Main` and calls `main(String[] args)`.

**Packages, imports, objects, references, exceptions** work as in any Java program: a **package** is a namespace (and here the architecture: `domain`, `application`, `infrastructure`, `interfaces`, `archunit.md`); `import` shortens a class name; `new X()` makes an object and gives a **reference**; the JVM garbage-collects unreferenced objects; a thrown `RuntimeException` is *unchecked*.

### The language features used here

- **Record.** A class whose fields are fixed at creation; Java writes the constructor, accessors and `equals`, `hashCode`, `toString`. Immutable.
- **Compact constructor.** Runs before the fields are set; validates or normalizes. `Held` uses it to cut the expiry time to milliseconds.
- **Sealed interface with nested records.** `sealed interface SeatState permits SeatState.Free, SeatState.Held, SeatState.Sold`: exactly three states, declared inside the interface.
- **Pattern-matching `switch` with guards.** `switch (current) { case Held held when held.customer().equals(customer) && ... -> ...; }` tests the type and adds a `when` condition. The compiler checks completeness for sealed types.
- **Interface as a port.** `SeatStore` and `Clock` list what the domain needs; adapters implement them.
- **Lambdas and `@FunctionalInterface`.** `Clock` has one method, so `() -> Instant.parse("...")` is a clock. `Function<SeatState, Outcome>` is a built-in function type.
- **Streams, `Optional`, immutable lists** (`List.of`, `List.copyOf`, `.toList()`).
- **`java.time`.** `Instant` is a moment on the timeline; `Duration` a length of time; `ChronoUnit.MILLIS` a unit.
- **`instanceof` patterns.** `if (outcome instanceof Success success)` tests the type and names the value in one step.

## Walkthrough of this repository's use

### `domain/SeatId.java`, `CustomerId.java`: ids that cannot be blank

File: `src/main/java/io/plainround/seatholds/domain/SeatId.java`

**Lines 1 to 8**

```java
package io.plainround.seatholds.domain;

/**
 * Identifier of a seat; never blank.
 *
 * @param value the id, such as {@code s-9}
 */
public record SeatId(String value) {
```

**Lines 10 to 20**

```java
  /**
   * Creates a seat id.
   *
   * @throws IllegalArgumentException if the value is null or blank
   */
  public SeatId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("seat id must not be blank");
    }
    value = value.strip();
  }
```

**Lines 22 to 26**

```java
  @Override
  public String toString() {
    return value;
  }
}
```

| Lines | What they do |
| --- | --- |
| 1 | `package ...domain;` puts the file in the domain layer; no imports are needed. |
| 8 | `public record SeatId(String value)`: an immutable one-field type. Wrapping the text in its own type stops a customer id being passed where a seat id belongs. Java generates the constructor, the accessor `value()`, `equals`, `hashCode` and `toString`. |
| 15-20 | The **compact constructor**: it runs before the field is set. Lines 16-18 refuse `null` or blank text (`isBlank()` is true for empty text or spaces only). Line 19 trims the text (`value = value.strip();`), so `new SeatId(" s-9 ")` equals `new SeatId("s-9")`. |
| 22-25 | `@Override` (the compiler checks it replaces an inherited method) `toString` returns the plain text, so logs print `s-9`. |

File: `src/main/java/io/plainround/seatholds/domain/CustomerId.java`

**Lines 1 to 8**

```java
package io.plainround.seatholds.domain;

/**
 * Identifier of a customer; never blank.
 *
 * @param value the id, such as {@code ada}
 */
public record CustomerId(String value) {
```

**Lines 10 to 20**

```java
  /**
   * Creates a customer id.
   *
   * @throws IllegalArgumentException if the value is null or blank
   */
  public CustomerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("customer id must not be blank");
    }
    value = value.strip();
  }
```

**Lines 22 to 26**

```java
  @Override
  public String toString() {
    return value;
  }
}
```

| Lines | What they do |
| --- | --- |
| 1 | `package ...domain;` puts the file in the domain layer. |
| 3-7 | A Javadoc comment (text for people, ignored by the compiler): what the type is, and `@param value` documents the one component. `{@code ada}` shows `ada` in code style. |
| 8 | `public record CustomerId(String value)`: the same one-field record as `SeatId`, a different type, so a customer id cannot be passed where a seat id belongs. |
| 10-14 | A Javadoc comment for the constructor; `@throws` documents when it refuses. |
| 15-20 | The compact constructor. Lines 16-18: if the text is `null` or blank, throw `IllegalArgumentException("customer id must not be blank")`. Line 19: replace the text with the trimmed text. |
| 22-25 | `toString` returns the plain text, so a log line prints `ada` and not `CustomerId[value=ada]`. |

### `domain/SeatState.java`: three states as data

File: `src/main/java/io/plainround/seatholds/domain/SeatState.java`

**Lines 1 to 12**

```java
package io.plainround.seatholds.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Where a seat is in its life: free, held for one customer until a time, or sold. Data, not an
 * object with behavior: the transitions are pure functions in {@link SeatTransitions}, and the
 * guarantee that a seat is sold at most once belongs to {@link SeatStore#update}, not to these
 * records.
 */
public sealed interface SeatState permits SeatState.Free, SeatState.Held, SeatState.Sold {
```

**Lines 14 to 24**

```java
  /** Nobody holds the seat and it has not been sold. */
  record Free() implements SeatState {}

  /**
   * One customer has the seat for a limited time.
   *
   * @param customer who holds it
   * @param expiresAt the last instant the hold can be confirmed, kept to the millisecond so every
   *     store, whatever its timestamp precision, compares equal values as equal
   */
  record Held(CustomerId customer, Instant expiresAt) implements SeatState {
```

**Lines 26 to 37**

```java
    /**
     * Normalizes the expiry to milliseconds.
     *
     * @throws IllegalArgumentException if the customer or the expiry is missing
     */
    public Held {
      if (customer == null || expiresAt == null) {
        throw new IllegalArgumentException("a hold needs a customer and an expiry");
      }
      expiresAt = expiresAt.truncatedTo(ChronoUnit.MILLIS);
    }
  }
```

**Lines 39 to 44**

```java
  /**
   * The seat is sold.
   *
   * @param customer who bought it
   */
  record Sold(CustomerId customer) implements SeatState {
```

**Lines 46 to 57**

```java
    /**
     * Validates the buyer.
     *
     * @throws IllegalArgumentException if the customer is missing
     */
    public Sold {
      if (customer == null) {
        throw new IllegalArgumentException("a sale needs a customer");
      }
    }
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-4 | Imports `Instant` (a moment on the timeline) and `ChronoUnit` (a unit of time such as milliseconds). |
| 12 | `public sealed interface SeatState permits SeatState.Free, SeatState.Held, SeatState.Sold`: an interface that is **sealed**, so only the three listed types may implement it. The compiler therefore knows every case. |
| 15 | `record Free() implements SeatState {}`: a record with **no** components: it carries no data. A record declared inside an interface is *nested*; `{}` is an empty body. |
| 24-37 | `record Held(CustomerId customer, Instant expiresAt)`: the holder and the last instant the hold can be confirmed. Its compact constructor (31-36) refuses a missing customer or expiry (32-34) and then **truncates the expiry to milliseconds** (35: `truncatedTo(ChronoUnit.MILLIS)`). `Instant` has nanosecond precision, PostgreSQL stores microseconds; cutting to milliseconds makes a value written to a database read back **equal**, so the compare-and-set compares equal values as equal. |
| 44-56 | `record Sold(CustomerId customer)`: the buyer, refused if missing (51-55). |

These records hold only **data**. The rules about what may follow what are in `SeatTransitions`, and the guarantee that a seat is sold at most once is in `SeatStore.update`.

### `domain/Outcome.java`

File: `src/main/java/io/plainround/seatholds/domain/Outcome.java`

**Lines 1 to 11**

```java
package io.plainround.seatholds.domain;

/** What a transition decides: the next state, or a reason there is none. Never an exception. */
public sealed interface Outcome permits Outcome.Success, Outcome.Reason {

  /**
   * The transition is allowed.
   *
   * @param newState the state the seat should move to
   */
  record Success(SeatState newState) implements Outcome {}
```

**Lines 13 to 19**

```java
  /**
   * The transition is refused.
   *
   * @param message why, in words a caller can show
   */
  record Reason(String message) implements Outcome {}
}
```

Line 4 declares another sealed interface with exactly two cases. Line 11, `record Success(SeatState newState)`, is the answer "allowed: move the seat to this state". Line 18, `record Reason(String message)`, is "refused, and here is why". A refusal is a normal answer, not an exception: callers must handle both because the interface is sealed.

### `domain/SeatTransitions.java`: pure functions with guarded patterns

File: `src/main/java/io/plainround/seatholds/domain/SeatTransitions.java`

**Lines 1 to 8**

```java
package io.plainround.seatholds.domain;

import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import java.time.Duration;
```

**Lines 10 to 19**

```java
/**
 * The seat's state machine as pure functions: each takes the current state and returns the next
 * state or a reason. Nothing here reads a store, mutates anything or throws, so every transition is
 * testable with a value and a fixed clock.
 *
 * <p>The edges are free to held (hold), held to sold (confirm, before the expiry), and held to free
 * (release, after the expiry). The instant of the expiry itself belongs to the customer: a confirm
 * at that instant succeeds and a release is refused, so the two cannot both be right.
 */
public final class SeatTransitions {
```

**Lines 21 to 33**

```java
  private SeatTransitions() {}

  /**
   * A customer asks to hold a seat.
   *
   * @param current the seat's state
   * @param customer who asks
   * @param clock the time source
   * @param holdFor how long the hold lasts
   * @return the hold, or why not
   */
  public static Outcome hold(
      SeatState current, CustomerId customer, Clock clock, Duration holdFor) {
```

**Lines 34 to 39**

```java
    return switch (current) {
      case Free free -> new Success(new Held(customer, clock.now().plus(holdFor)));
      case Held held -> new Reason("seat is held");
      case Sold sold -> new Reason("already sold");
    };
  }
```

**Lines 41 to 53**

```java
  /**
   * A customer confirms the hold they were given.
   *
   * @param current the seat's state
   * @param customer who confirms
   * @param clock the time source
   * @return the sale, or why not
   */
  public static Outcome confirm(SeatState current, CustomerId customer, Clock clock) {
    return switch (current) {
      case Held held
          when held.customer().equals(customer) && !clock.now().isAfter(held.expiresAt()) ->
          new Success(new Sold(customer));
```

**Lines 54 to 59**

```java
      case Held held when held.customer().equals(customer) -> new Reason("hold expired");
      case Held held -> new Reason("held by someone else");
      case Free free -> new Reason("not held");
      case Sold sold -> new Reason("already sold");
    };
  }
```

**Lines 61 to 73**

```java
  /**
   * The sweeper frees a hold whose time has run out.
   *
   * @param current the seat's state
   * @param clock the time source
   * @return the freed seat, or why not
   */
  public static Outcome release(SeatState current, Clock clock) {
    return switch (current) {
      case Held held when clock.now().isAfter(held.expiresAt()) -> new Success(new Free());
      case Held held -> new Reason("hold not expired");
      case Free free -> new Reason("not held");
      case Sold sold -> new Reason("already sold");
```

**Lines 74 to 76**

```java
    };
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-8 | Imports the two outcomes, the three states and `Duration`. |
| 19-21 | A `final` class with a private constructor: it only holds static methods. **Pure** means each answer depends only on the arguments, and nothing outside is read or changed (no store, no thread, no exception). |
| 32-39 | `hold(current, customer, clock, holdFor)`: `switch (current)` tests which state the seat is in. `case Free free ->` matches a free seat and names it `free`; the result is `new Success(new Held(customer, clock.now().plus(holdFor)))`: held by this customer until now plus the hold length. A held or sold seat gives `Reason("seat is held")` or `Reason("already sold")`. The three cases cover the sealed interface, so there is no `default`. |
| 49-59 | `confirm(current, customer, clock)`. A `switch` checks the cases **in order; the first match wins**. Lines 51-53: `case Held held when held.customer().equals(customer) && !clock.now().isAfter(held.expiresAt()) -> ...` is a **guarded pattern**: a held seat **and** (`when`) the confirming customer is the holder **and** the time is not after the expiry gives `Success(new Sold(customer))`. Line 54: the same holder but too late gives `Reason("hold expired")` (the first guard failed on time only). Line 55: any other held seat is held by someone else. Line 56: a free seat is "not held". Line 57: a sold seat is "already sold". |
| 68-75 | `release(current, clock)`: only a `Held` seat whose expiry is **before** now (`clock.now().isAfter(held.expiresAt())`) becomes `Free` (line 70); an unexpired hold, a free seat and a sold seat each give a reason. |

The boundary is on purpose complementary: `confirm` uses `!now.isAfter(expiresAt)` (still allowed at the exact expiry instant) and `release` uses `now.isAfter(expiresAt)` (not allowed at the exact instant), so at any instant exactly one of the two is allowed. `SeatTransitionsTest` proves that.

### `domain/Clock.java`, `SeatStore.java`, the exceptions: the ports

File: `src/main/java/io/plainround/seatholds/domain/Clock.java`

**Lines 1 to 7**

```java
package io.plainround.seatholds.domain;

import java.time.Instant;

/** Outbound port: the current time, injected so an expired hold can be tested without waiting. */
@FunctionalInterface
public interface Clock {
```

**Lines 9 to 15**

```java
  /**
   * The current instant.
   *
   * @return now
   */
  Instant now();
}
```

| Lines | What they do |
| --- | --- |
| 3 | Imports `Instant`. |
| 6 | `@FunctionalInterface`: the compiler checks there is exactly one abstract method, so a **lambda** can be a clock. |
| 7 | `public interface Clock`: the **port** for time. |
| 14 | `Instant now();` is the one method: the current moment. Injecting it lets a test fix time (`MutableClock`), so an expired hold is tested without waiting. |

File: `src/main/java/io/plainround/seatholds/domain/SeatStore.java`

**Lines 1 to 10**

```java
package io.plainround.seatholds.domain;

import java.util.List;

/**
 * Outbound port: where seat states live. This is where "a seat is sold at most once" is enforced:
 * {@link #update} is a compare-and-set, so a write based on stale data cannot win. Every adapter
 * must pass the same contract test.
 */
public interface SeatStore {
```

**Lines 12 to 19**

```java
  /**
   * Reads a seat's current state.
   *
   * @param seat the seat
   * @return its state
   * @throws UnknownSeatException if the seat was never added
   */
  SeatState get(SeatId seat);
```

**Lines 21 to 31**

```java
  /**
   * Moves a seat to a new state only if it is still in the state the caller read.
   *
   * @param seat the seat
   * @param expected the state the caller based its decision on
   * @param next the state to move to
   * @return true only if the seat was in {@code expected} and {@code next} was applied; false if
   *     someone else changed it first
   * @throws UnknownSeatException if the seat was never added
   */
  boolean update(SeatId seat, SeatState expected, SeatState next);
```

**Lines 33 to 39**

```java
  /**
   * Adds a seat in the free state unless it already exists.
   *
   * @param seat the seat
   * @return true if it was added, false if it was already there
   */
  boolean add(SeatId seat);
```

**Lines 41 to 47**

```java
  /**
   * Every seat the store knows, in id order.
   *
   * @return an immutable list
   */
  List<SeatId> seats();
}
```

| Lines | What they do |
| --- | --- |
| 3 | Imports `List`. |
| 10 | `public interface SeatStore`: the most important interface in the repository. Its Javadoc says where "a seat is sold at most once" is enforced. |
| 19 | `SeatState get(SeatId seat);` reads a state; throws `UnknownSeatException` for a seat never added. |
| 31 | `boolean update(SeatId seat, SeatState expected, SeatState next);` is a **compare-and-set**: write `next` only if the seat is still in `expected`; return `true` only if that happened, `false` if someone changed it first. |
| 39 | `boolean add(SeatId seat);` adds a free seat unless it exists; returns whether it added one. |
| 46 | `List<SeatId> seats();` lists every seat, in id order. |

File: `src/main/java/io/plainround/seatholds/domain/UnknownSeatException.java`

**Lines 1 to 6**

```java
package io.plainround.seatholds.domain;

/** The caller named a seat the store has never been given. */
public final class UnknownSeatException extends RuntimeException {

  private static final long serialVersionUID = 1L;
```

**Lines 8 to 16**

```java
  /**
   * Creates the exception.
   *
   * @param seat the unknown seat
   */
  public UnknownSeatException(SeatId seat) {
    super("unknown seat: " + seat);
  }
}
```

A named error. Line 4: a `final` class extending `RuntimeException` (unchecked). Line 6: the `serialVersionUID` constant that `-Xlint:all` asks for. Lines 13-15: the constructor takes the `SeatId` and calls `super("unknown seat: " + seat)`, storing the message in the parent class.

File: `src/main/java/io/plainround/seatholds/domain/SeatStoreException.java`

**Lines 1 to 6**

```java
package io.plainround.seatholds.domain;

/** The store could not be reached or answered with an error; the seat's state is unknown. */
public final class SeatStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;
```

**Lines 8 to 17**

```java
  /**
   * Creates the exception.
   *
   * @param message what the store was asked to do
   * @param cause the underlying failure
   */
  public SeatStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

The same, with a **cause**: the constructor (lines 14-16) takes a message and the underlying `Throwable` and calls `super(message, cause)`, so the original database error is kept and shown in the stack trace. It means "the store could not be reached or answered with an error", so the seat's state is unknown.

### `application/`: the inbound ports and the service

File: `src/main/java/io/plainround/seatholds/application/HoldSeats.java`

**Lines 1 to 8**

```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: a customer wants a seat held. Called by an adapter; never calls out to one. */
public interface HoldSeats {
```

**Lines 10 to 18**

```java
  /**
   * Holds a seat for a customer.
   *
   * @param seat the seat
   * @param customer who wants it
   * @return the new state, or why the seat cannot be held
   */
  Outcome hold(SeatId seat, CustomerId customer);
}
```

| Lines | What they do |
| --- | --- |
| 3-5 | Imports the domain types used in the signature. |
| 8 | `public interface HoldSeats`: an **inbound port**, a thing callers (the command line, a test, later an HTTP handler) can call into. |
| 17 | `Outcome hold(SeatId seat, CustomerId customer);` has no body: it says what a caller wants (hold this seat for this customer) and what comes back (`Success` with the new state, or a `Reason`). |

File: `src/main/java/io/plainround/seatholds/application/ConfirmHold.java`

**Lines 1 to 8**

```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: a customer turns their hold into a sale. */
public interface ConfirmHold {
```

**Lines 10 to 18**

```java
  /**
   * Confirms a hold.
   *
   * @param seat the seat
   * @param customer who holds it
   * @return the sold state, or why it cannot be confirmed
   */
  Outcome confirm(SeatId seat, CustomerId customer);
}
```

| Lines | What they do |
| --- | --- |
| 1 | `package ...application;`: the application layer. |
| 3-5 | Imports `CustomerId`, `Outcome` and `SeatId` from the domain layer (application may depend on domain, never the reverse; `archunit.md`). |
| 7 | A one-line Javadoc comment: "Inbound port: a customer turns their hold into a sale." |
| 8 | `public interface ConfirmHold`: an **inbound port** with one method, the same shape as `HoldSeats`. |
| 10-16 | The Javadoc of the method; `@param` documents each argument and `@return` what comes back. |
| 17 | `Outcome confirm(SeatId seat, CustomerId customer);` has no body. It says what a caller wants (confirm this seat for this customer) and what comes back: a `Success` carrying the new `Sold` state, or a `Reason` explaining the refusal. |

File: `src/main/java/io/plainround/seatholds/application/ExpireHolds.java`

**Lines 1 to 7**

```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: free holds whose time has run out. Called by a scheduler. */
public interface ExpireHolds {
```

**Lines 9 to 14**

```java
  /**
   * Sweeps all seats once.
   *
   * @return how many holds were released
   */
  int sweep();
```

**Lines 16 to 23**

```java
  /**
   * Releases one seat's hold if, and only if, it has expired.
   *
   * @param seat the seat
   * @return the freed seat, or why it was left alone
   */
  Outcome release(SeatId seat);
}
```

Line 7 declares the third inbound port. Line 14, `int sweep();`, frees every expired hold in one pass and returns how many. Line 22, `Outcome release(SeatId seat);`, releases one seat if, and only if, its hold has expired (the race driver and `sweep` use it).

File: `src/main/java/io/plainround/seatholds/application/SeatHoldService.java`

**Lines 1 to 14**

```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.Clock;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatTransitions;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
```

**Lines 15 to 22**

```java
import java.util.function.Function;

/**
 * The use cases. Each reads the seat, asks a pure transition what should happen, and writes the
 * result through the store's compare-and-set. If the write loses a race, it reads again and decides
 * again; a state that changed under it may make a different transition right, or none.
 */
public final class SeatHoldService implements HoldSeats, ConfirmHold, ExpireHolds {
```

**Lines 24 to 29**

```java
  private static final Logger LOG = System.getLogger(SeatHoldService.class.getName());
  private static final int MAX_ATTEMPTS = 5;

  private final SeatStore store;
  private final Clock clock;
  private final Duration holdFor;
```

**Lines 31 to 42**

```java
  /**
   * Creates the service.
   *
   * @param store where seats live
   * @param clock the time source
   * @param holdFor how long a hold lasts
   */
  public SeatHoldService(SeatStore store, Clock clock, Duration holdFor) {
    this.store = store;
    this.clock = clock;
    this.holdFor = holdFor;
  }
```

**Lines 44 to 50**

```java
  @Override
  public Outcome hold(SeatId seat, CustomerId customer) {
    Outcome outcome =
        decideAndWrite(seat, current -> SeatTransitions.hold(current, customer, clock, holdFor));
    log("hold", seat, customer, outcome);
    return outcome;
  }
```

**Lines 52 to 58**

```java
  @Override
  public Outcome confirm(SeatId seat, CustomerId customer) {
    Outcome outcome =
        decideAndWrite(seat, current -> SeatTransitions.confirm(current, customer, clock));
    log("confirm", seat, customer, outcome);
    return outcome;
  }
```

**Lines 60 to 69**

```java
  @Override
  public int sweep() {
    int released = 0;
    for (SeatId seat : store.seats()) {
      if (release(seat) instanceof Success) {
        released++;
      }
    }
    return released;
  }
```

**Lines 71 to 78**

```java
  @Override
  public Outcome release(SeatId seat) {
    Outcome outcome = decideAndWrite(seat, current -> SeatTransitions.release(current, clock));
    if (outcome instanceof Success) {
      LOG.log(Level.INFO, "event=released seat={0}", seat);
    }
    return outcome;
  }
```

**Lines 80 to 92**

```java
  private Outcome decideAndWrite(SeatId seat, Function<SeatState, Outcome> transition) {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      SeatState current = store.get(seat);
      Outcome outcome = transition.apply(current);
      if (!(outcome instanceof Success success)) {
        return outcome;
      }
      if (store.update(seat, current, success.newState())) {
        return outcome;
      }
    }
    return new Reason("seat is contended, try again");
  }
```

**Lines 94 to 106**

```java
  /** Successes are INFO; a refusal is routine under contention, so it is DEBUG (FINE). */
  private static void log(String action, SeatId seat, CustomerId customer, Outcome outcome) {
    if (outcome instanceof Reason reason) {
      LOG.log(
          Level.DEBUG,
          "event={0} seat={1} customer={2} outcome=refused reason=\"{3}\"",
          action,
          seat,
          customer,
          reason.message());
    } else {
      LOG.log(Level.INFO, "event={0} seat={1} customer={2} outcome=ok", action, seat, customer);
    }
```

**Lines 107 to 108**

```java
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-15 | Imports. `Function` is a built-in type for "a function taking one value and returning another"; `Logger` and `Level` are Java's logging types. |
| 22 | `public final class SeatHoldService implements HoldSeats, ConfirmHold, ExpireHolds`: one class promising all three interfaces. |
| 24 | The class's logger. |
| 25 | `MAX_ATTEMPTS = 5`: how many times to read-decide-write before giving up. A constant, so the number has a name. |
| 27-29 | Three `private final` fields: the store (the port), the clock (the port) and the hold length. |
| 38-42 | The constructor stores the three arguments; the service creates nothing itself (**dependency injection**: tests give it a fake clock and any store). |
| 44-50 | `hold`: line 46-47 calls `decideAndWrite(seat, current -> SeatTransitions.hold(current, customer, clock, holdFor))`. The second argument is a **lambda**: a small anonymous function that takes the seat's current state and asks the pure transition what should happen. Line 48 logs the result; line 49 returns it. |
| 52-58 | `confirm` is the same with `SeatTransitions.confirm`. |
| 60-69 | `sweep`: `released` counts; `for (SeatId seat : store.seats())` visits every seat; `release(seat) instanceof Success` is true when that seat was freed; the counter is incremented and returned. |
| 71-78 | `release`: decides with `SeatTransitions.release`, logs `event=released` at `INFO` when it worked, returns the outcome. |
| 80-92 | `decideAndWrite`, **the heart of the class**. `Function<SeatState, Outcome> transition` is the lambda passed in. Line 81 loops at most five times. Line 82 **reads** the seat. Line 83 **decides** (`transition.apply(current)`). Lines 84-86: `!(outcome instanceof Success success)` asks "is the answer not a success?"; if so, return it at once (a refusal needs no write). The pattern `Success success` also names the value when it *is* a success, so line 87 can use `success.newState()`. Line 87 **writes** through the store's compare-and-set with the state it read as the expected one; if it returns `true`, the seat moved and the outcome is returned (88). If it returns `false`, someone else changed the seat in between, and the loop starts again: it **reads again and decides again**, because a changed seat may need a different decision (or none). After five tries, line 91 returns `Reason("seat is contended, try again")` instead of looping forever. |
| 95-107 | `log`: a refusal (`outcome instanceof Reason reason`) is written at `DEBUG` with its message, because refusals are routine under contention; a success is written at `INFO`. The `{0}`..`{3}` places in the text are filled by the arguments after it. |

### What other files cover

- `application/simulation/` (the race drivers), `infrastructure/memory/` (the in-memory store and the counter-example), `infrastructure/scheduling/ScheduledSweeper.java` and `interfaces/cli/Main.java` (the shutdown hook): `java-concurrency.md`.
- `infrastructure/jdbc/` and `sql/`: `jdbc.md`, `hikaricp.md`, `sql.md`, `postgresql.md`.
- `interfaces/cli/Logging.java`: `java-logging.md`. `interfaces/cli/AppConfig.java`: `environment-variables.md`.

### `infrastructure/time/SystemClock.java`: the real clock

File: `src/main/java/io/plainround/seatholds/infrastructure/time/SystemClock.java`

**Lines 1 to 7**

```java
package io.plainround.seatholds.infrastructure.time;

import io.plainround.seatholds.domain.Clock;
import java.time.Instant;

/** The real clock. Tests use a fixed one instead. */
public final class SystemClock implements Clock {
```

**Lines 9 to 13**

```java
  @Override
  public Instant now() {
    return Instant.now();
  }
}
```

| Lines | What they do |
| --- | --- |
| 1 | `package ...infrastructure.time;`: the infrastructure layer, sub-package `time`. |
| 3-4 | Imports the domain's `Clock` interface and Java's `Instant` (one exact moment on the timeline). |
| 6 | A Javadoc comment: "The real clock. Tests use a fixed one instead." |
| 7 | `public final class SystemClock implements Clock`: the class promises to supply the one method of the domain's `Clock` interface. The domain asks "what time is it?" through the interface and never learns that this class exists. |
| 9 | `@Override`: tells the compiler this method replaces one declared in the interface; a typo in the name becomes a compile error. |
| 10-12 | `public Instant now() { return Instant.now(); }`: ask the computer's clock for the current moment and return it. Because the time comes in through the interface, tests give the service a `MutableClock` (`junit-test-walkthrough.md`) whose time they control. |

### `interfaces/cli/ConfigurationException.java`: a named error for bad settings

File: `src/main/java/io/plainround/seatholds/interfaces/cli/ConfigurationException.java`

**Lines 1 to 6**

```java
package io.plainround.seatholds.interfaces.cli;

/** An environment variable holds a value the program cannot use. */
public final class ConfigurationException extends RuntimeException {

  private static final long serialVersionUID = 1L;
```

**Lines 8 to 16**

```java
  /**
   * Creates the exception.
   *
   * @param message which variable is wrong and what is expected
   */
  public ConfigurationException(String message) {
    super(message);
  }
}
```

| Lines | What they do |
| --- | --- |
| 1 | `package ...;` puts the class in the command-line package. |
| 3 | A one-line Javadoc comment (`/** ... */`): documentation for people, ignored by the compiler. It says what the error means: an environment variable holds a value the program cannot use. |
| 4 | `public final class ConfigurationException extends RuntimeException`: a new error type. `public` lets other packages use it; `final` forbids subclasses; `extends RuntimeException` makes it an **unchecked** exception: the compiler does not force callers to write `try/catch` for it. |
| 5 | A blank line. |
| 6 | `private static final long serialVersionUID = 1L;`: Java can turn an object into bytes (**serialization**), and exceptions are allowed to be serialized. This number is the version tag of that byte format. It is `private` (hidden), `static` (one value for the class), `final` (never changes), and `1L` is the number 1 as a `long` (the `L` says so). The compiler flag `-Xlint:all` with `-Werror` (`javac.md`) turns a missing one into a build failure, which is why it is here. |
| 8-12 | A Javadoc comment for the constructor; `@param message` documents the argument: which variable is wrong and what is expected. |
| 13 | `public ConfigurationException(String message)`: the constructor takes the text of the message. |
| 14 | `super(message);` hands the text to `RuntimeException`, which stores it; `getMessage()` returns it later. |
| 15-16 | The closing braces of the constructor and the class. |

### `interfaces/cli/Application.java`: the composition root

File: `src/main/java/io/plainround/seatholds/interfaces/cli/Application.java`

**Lines 1 to 11**

```java
package io.plainround.seatholds.interfaces.cli;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatStoreException;
import io.plainround.seatholds.infrastructure.jdbc.JdbcSeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.infrastructure.time.SystemClock;
import java.util.List;
import java.util.stream.IntStream;
```

**Lines 13 to 19**

```java
/** The composition root: builds the chosen store and the service, and closes what it opened. */
final class Application implements AutoCloseable {

  private final AutoCloseable resource;
  private final SeatStore store;
  private final SeatHoldService service;
  private final List<SeatId> seatIds;
```

**Lines 21 to 33**

```java
  Application(AppConfig config) {
    if (config.store() == AppConfig.StoreKind.JDBC) {
      JdbcSeatStore jdbc = JdbcSeatStore.open(config.database());
      store = jdbc;
      resource = jdbc;
    } else {
      store = new InMemorySeatStore();
      resource = () -> {};
    }
    seatIds = IntStream.rangeClosed(1, config.seats()).mapToObj(n -> new SeatId("s-" + n)).toList();
    seatIds.forEach(store::add);
    service = new SeatHoldService(store, new SystemClock(), config.holdFor());
  }
```

**Lines 35 to 41**

```java
  SeatStore store() {
    return store;
  }

  SeatHoldService service() {
    return service;
  }
```

**Lines 43 to 55**

```java
  List<SeatId> seatIds() {
    return seatIds;
  }

  @Override
  public void close() {
    try {
      resource.close();
    } catch (Exception failure) {
      throw new SeatStoreException("closing the store", failure);
    }
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-11 | Imports everything it **chooses and builds**: the service, the two store adapters, the system clock, `List` and `IntStream`. The composition root is the one place allowed to know all the concrete classes. |
| 14 | `final class Application implements AutoCloseable`: package-private (no `public`). `AutoCloseable` means it has a `close()` method, so it can be used in a `try (...)` and is closed automatically. |
| 16-19 | Four `private final` fields: whatever needs closing (`resource`), the store (typed as the port), the service, and the list of seat ids. |
| 21-33 | The constructor. Lines 22-25: if the settings say `JDBC`, open a `JdbcSeatStore` (which owns its connection pool) and remember it both as the store and as the thing to close. Lines 26-29: otherwise create the in-memory store, and `resource = () -> {}` is a lambda that does nothing, because an in-memory store needs no closing. Line 30: `IntStream.rangeClosed(1, config.seats())` makes the numbers 1 to N, `.mapToObj(n -> new SeatId("s-" + n))` turns each into a seat id (`s-1`..), `.toList()` collects them into an unchangeable list. Line 31: `seatIds.forEach(store::add)` adds each seat through the port (`store::add` is a method reference, short for `id -> store.add(id)`); adding is harmless if the seat exists. Line 32 builds the service with the store, the real clock and the hold length. |
| 35-45 | Three one-line getters: `store()`, `service()`, `seatIds()`. |
| 47-54 | `close()`: closes the resource; `Exception` is the broad parent of every checked exception, so `catch (Exception failure)` handles whatever `AutoCloseable.close` may throw; it is rethrown as a `SeatStoreException`. |

### `interfaces/cli/AppConfig.java`

File: `src/main/java/io/plainround/seatholds/interfaces/cli/AppConfig.java`

**Lines 1 to 7**

```java
package io.plainround.seatholds.interfaces.cli;

import io.plainround.seatholds.infrastructure.jdbc.DatabaseSettings;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
```

**Lines 9 to 21**

```java
/**
 * Settings read from the environment and validated once at start-up; see {@code .env.example}.
 *
 * @param store which adapter holds the seats
 * @param database where the database is; present only for the JDBC store
 * @param holdFor how long a hold lasts
 * @param sweepEvery the delay between expiry sweeps
 * @param seats how many seats {@code s-1} to {@code s-N} the store is given at start-up
 * @param logLevel the lowest log level written to standard error
 */
public record AppConfig(
    StoreKind store,
    DatabaseSettings database,
```

**Lines 22 to 33**

```java
    Duration holdFor,
    Duration sweepEvery,
    int seats,
    String logLevel) {

  /** The seat store adapters. */
  public enum StoreKind {
    /** A concurrent map in this process. */
    MEMORY,
    /** PostgreSQL through JDBC. */
    JDBC
  }
```

**Lines 35 to 47**

```java
  /**
   * Reads and validates the settings.
   *
   * @param env the environment variables
   * @return the validated settings
   * @throws ConfigurationException if a value is malformed or out of range
   */
  public static AppConfig from(Map<String, String> env) {
    try {
      StoreKind store =
          StoreKind.valueOf(env.getOrDefault("STORE", "memory").toUpperCase(Locale.ROOT));
      DatabaseSettings database =
          store == StoreKind.JDBC
```

**Lines 48 to 61**

```java
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
```

**Lines 62 to 67**

```java
      Logging.requireKnownLevel(level);
      return new AppConfig(store, database, holdFor, sweepEvery, seats, level);
    } catch (DateTimeParseException | IllegalArgumentException problem) {
      throw new ConfigurationException("invalid configuration: " + problem.getMessage());
    }
  }
```

**Lines 69 to 77**

```java
  private static Duration positive(String name, String text) {
    Duration duration = Duration.parse(text);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(
          name + " must be a positive ISO-8601 duration, was " + text);
    }
    return duration;
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-7 | Imports the database settings type, `Duration` (a length of time), the parse error for times, `Locale` and `Map`. |
| 19-25 | `public record AppConfig(StoreKind store, DatabaseSettings database, Duration holdFor, Duration sweepEvery, int seats, String logLevel)`: the validated settings as an immutable record. |
| 28-33 | `public enum StoreKind { MEMORY, JDBC }`: an **enum** is a type with a fixed list of values; here the two store choices. |
| 42 | `public static AppConfig from(Map<String, String> env)`: a factory method that builds the settings from the environment. |
| 43, 64 | A `try`/`catch` around everything: any problem becomes one clear `ConfigurationException`. |
| 44-45 | `StoreKind.valueOf(...)` turns the text into the enum value (`MEMORY` or `JDBC`) and throws `IllegalArgumentException` for anything else (for example `mongo`). `toUpperCase(Locale.ROOT)` accepts `memory` or `JDBC`. |
| 46-54 | The database settings are built **only** for the JDBC store (a conditional expression `cond ? a : b`; otherwise `null`). `env.get(...)` returns `null` for a missing variable, and the `DatabaseSettings` constructor refuses it with a clear message. `Integer.parseInt` reads the pool size; `positive(...)` parses the connection timeout. |
| 55-56 | The hold length (default ten minutes) and the sweep interval (default 30 seconds), both parsed by `positive`. |
| 57-60 | `SEATS` is parsed as a whole number and must be at least 1. |
| 61-62 | The log level is upper-cased and checked. |
| 63 | Returns the settings. |
| 64-66 | `catch (DateTimeParseException | IllegalArgumentException problem)`: a **multi-catch**. (`NumberFormatException` is an `IllegalArgumentException`.) The message is prefixed `invalid configuration: `. |
| 69-76 | `positive(name, text)`: `Duration.parse(text)` reads an **ISO-8601 duration** such as `PT10M` (`P` starts a period, `T` starts the time part, `10M` is ten minutes; `PT30S` is thirty seconds). It rejects zero or negative durations with a message naming the variable. |

### `interfaces/cli/Cli.java`

File: `src/main/java/io/plainround/seatholds/interfaces/cli/Cli.java`

**Lines 1 to 14**

```java
package io.plainround.seatholds.interfaces.cli;

import io.plainround.seatholds.application.simulation.HoldRace;
import io.plainround.seatholds.application.simulation.RaceResult;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatStoreException;
import io.plainround.seatholds.domain.UnknownSeatException;
import io.plainround.seatholds.infrastructure.scheduling.ScheduledSweeper;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
```

**Lines 15 to 21**

```java
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

/** Command-line driver: parses arguments, calls the use cases, prints results. */
public final class Cli {
```

**Lines 23 to 35**

```java
  private static final String USAGE =
      """
      usage: seatholds <command>
        scenario                   the lesson's scenarios on the configured store, and the failure they guard against
        hold <seat> <customer>     hold a seat (it lasts HOLD_DURATION)
        confirm <seat> <customer>  turn a hold into a sale
        status                     print every seat's state
        sweep                      free every expired hold once
        race [runs] [customers]    race customers for 10 fresh seats; default 5 runs of 1000
        serve                      sweep every SWEEP_INTERVAL until interrupted, then stop cleanly
        health                     check the configuration and the store, exit 0 when usable
        help                       print this text
      settings come from the environment: STORE=memory|jdbc, DATABASE_URL, DATABASE_USER, ... (see .env.example)
```

**Lines 36 to 41**

```java
      """;

  private final Map<String, String> env;
  private final PrintStream out;
  private final PrintStream err;
  private final CountDownLatch shutdown;
```

**Lines 43 to 55**

```java
  /**
   * Creates a driver.
   *
   * @param env environment variables
   * @param out standard output
   * @param err standard error
   * @param shutdown counted down by the process's shutdown signal; {@code serve} runs until then
   */
  public Cli(Map<String, String> env, PrintStream out, PrintStream err, CountDownLatch shutdown) {
    this.env = env;
    this.out = out;
    this.err = err;
    this.shutdown = shutdown;
```

**Lines 56 to 69**

```java
  }

  /**
   * Runs one command.
   *
   * @param args the command line
   * @return the exit code: 0 on success, 1 when a check or invariant fails or the store is
   *     unavailable, 2 on bad usage or settings
   */
  public int run(String... args) {
    String command = args.length == 0 ? "help" : args[0];
    if (command.equals("help")) {
      out.print(USAGE);
      return 0;
```

**Lines 70 to 83**

```java
    }
    AppConfig config;
    try {
      config = AppConfig.from(env);
    } catch (ConfigurationException problem) {
      err.println(problem.getMessage());
      return 2;
    }
    Logging.configure(config.logLevel());
    try (Application app = new Application(config)) {
      return dispatch(command, args, config, app);
    } catch (SeatStoreException problem) {
      err.println("store unavailable: " + problem.getMessage() + ": " + problem.getCause());
      return 1;
```

**Lines 84 to 97**

```java
    } catch (IllegalArgumentException problem) {
      err.println(problem.getMessage());
      return 2;
    }
  }

  private int dispatch(String command, String[] args, AppConfig config, Application app) {
    try {
      return switch (command) {
        case "scenario" -> scenario(app);
        case "hold" ->
            twoArguments(args, (seat, customer) -> print(app.service().hold(seat, customer)));
        case "confirm" ->
            twoArguments(args, (seat, customer) -> print(app.service().confirm(seat, customer)));
```

**Lines 98 to 111**

```java
        case "status" -> status(app);
        case "sweep" -> {
          out.println("released " + app.service().sweep() + " expired hold(s)");
          yield 0;
        }
        case "race" -> race(args, app);
        case "serve" -> serve(config, app);
        case "health" -> health(config, app);
        default -> {
          err.print(USAGE);
          yield 2;
        }
      };
    } catch (UnknownSeatException problem) {
```

**Lines 112 to 121**

```java
      err.println(problem.getMessage());
      return 1;
    }
  }

  private int scenario(Application app) {
    String runId = Long.toString(Instant.now().toEpochMilli(), 36);
    boolean held = new Scenario(app.store(), app.service(), runId, out).run();
    return held ? 0 : 1;
  }
```

**Lines 123 to 133**

```java
  private interface SeatAction {
    int run(SeatId seat, CustomerId customer);
  }

  private int twoArguments(String[] args, SeatAction action) {
    if (args.length != 3) {
      err.print(USAGE);
      return 2;
    }
    return action.run(new SeatId(args[1]), new CustomerId(args[2]));
  }
```

**Lines 135 to 145**

```java
  private int print(Outcome outcome) {
    out.println(outcome);
    return outcome instanceof Success ? 0 : 1;
  }

  private int status(Application app) {
    for (SeatId seat : app.store().seats()) {
      out.println(seat + " " + app.store().get(seat));
    }
    return 0;
  }
```

**Lines 147 to 159**

```java
  private int race(String[] args, Application app) {
    int runs = args.length > 1 ? Integer.parseInt(args[1]) : 5;
    int customers = args.length > 2 ? Integer.parseInt(args[2]) : 1_000;
    String runId = Long.toString(Instant.now().toEpochMilli(), 36);
    boolean held = true;
    for (int run = 1; run <= runs; run++) {
      String label = "race-" + run + "-" + runId;
      List<SeatId> seats =
          IntStream.rangeClosed(1, 10).mapToObj(n -> new SeatId(label + "-" + n)).toList();
      seats.forEach(app.store()::add);
      RaceResult result = new HoldRace(app.service()).run(seats, customers);
      out.println("race result: " + result);
      held &= result.wins() == Math.min(10, customers);
```

**Lines 160 to 173**

```java
    }
    return held ? 0 : 1;
  }

  private int serve(AppConfig config, Application app) {
    ScheduledSweeper sweeper = new ScheduledSweeper(app.service(), config.sweepEvery());
    try {
      out.println(
          "serving: sweeping expired holds every " + config.sweepEvery() + "; Ctrl-C to stop");
      shutdown.await();
      out.println("stopping: finishing the sweep in progress");
      return 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
```

**Lines 174 to 187**

```java
      return 1;
    } finally {
      sweeper.close();
    }
  }

  private int health(AppConfig config, Application app) {
    app.store().seats();
    out.println(
        "ok store="
            + config.store().name().toLowerCase(Locale.ROOT)
            + " seats="
            + app.seatIds().size());
    return 0;
```

**Lines 188 to 189**

```java
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-18 | Imports: the race driver and its result, domain types, the scheduler, `PrintStream`, `Instant`, `List`, `Locale`, `Map`, `CountDownLatch` (a gate threads wait at, `java-concurrency.md`) and `IntStream`. |
| 23-36 | `USAGE`, a **text block** (`"""`): the help text. |
| 38-41 | Four `private final` fields: the environment, the output stream, the error stream and the shutdown latch. Everything from outside arrives through the constructor, so tests can supply fakes. |
| 51-56 | The constructor stores them. |
| 65 | `public int run(String... args)`: **varargs** (any number of words); returns the exit code. |
| 66-70 | No word means `help`. `help` prints the usage and returns 0 before reading settings. |
| 71-77 | Reads the settings. A `ConfigurationException` prints its message to standard error and returns 2. |
| 78 | Configures logging (`java-logging.md`). |
| 79-87 | `try (Application app = new Application(config))`: **try-with-resources** builds the store and service and **closes** them when the block ends, however it ends. Line 80 runs the command. `catch (SeatStoreException problem)` (store unreachable) prints `store unavailable: ...` and returns 1; `catch (IllegalArgumentException problem)` (for example a bad number in a command) prints the message and returns 2. |
| 90-115 | `dispatch`: a `switch` **expression** over the command word. `scenario` calls `scenario(app)`. `hold` and `confirm` call `twoArguments(args, (seat, customer) -> print(...))`: the second argument is a **lambda** that will be run with the seat and customer once the arguments are checked. `status`, `race`, `serve` and `health` call their methods. `sweep` (99-102) is a block: it prints how many holds the sweep released, then `yield 0` gives the block's value. `default` prints the usage to standard error and yields 2. `catch (UnknownSeatException problem)` (111-114) prints the message and returns 1. |
| 117-121 | `scenario`: `Long.toString(Instant.now().toEpochMilli(), 36)` turns the current time in milliseconds into a short text in base 36 (digits and letters): a **run id** that makes every seat id of this run unique. The `Scenario` returns `true` if every invariant held, mapped to exit code 0 or 1. |
| 123-125 | A private nested `interface SeatAction` with one method: it lets a lambda be passed to `twoArguments`. |
| 127-133 | `twoArguments`: the command must have exactly three words (`hold`, seat, customer), otherwise usage and 2; then it builds the two ids and runs the action. |
| 135-138 | `print`: prints the outcome (a record, so it prints like `Success[newState=...]`) and returns 0 for a `Success`, 1 for a refusal. |
| 140-145 | `status`: a `for` loop prints each seat and its state. |
| 147-162 | `race`: optional words give the number of runs (default 5) and customers (default 1,000; `1_000` is just 1000, the underscore helps reading). Lines 152-160 loop over the runs: build a label, make ten seat ids with `IntStream`, add them to the store (`seats.forEach(app.store()::add)`), run `HoldRace` (the thousand-thread race), print `race result: wins=10 losses=990`, and accumulate with `held &= ...`, which means `held = held && (...)`: the wins must equal ten (or the customer count if smaller). |
| 164-178 | `serve`: creates the sweeper (a background thread, `java-concurrency.md`), prints a line, then `shutdown.await()` **waits** until the shutdown signal counts the latch down, prints `stopping`, returns 0. `catch (InterruptedException ...)` restores the thread's interrupt flag and returns 1. `finally` runs the sweeper's `close()` whatever happened. |
| 180-188 | `health`: calling `app.store().seats()` proves the store answers (for the database, that it is reachable); prints `ok store=memory seats=10`. |

### `interfaces/cli/Scenario.java`

File: `src/main/java/io/plainround/seatholds/interfaces/cli/Scenario.java`

**Lines 1 to 14**

```java
package io.plainround.seatholds.interfaces.cli;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.application.simulation.HoldRace;
import io.plainround.seatholds.application.simulation.RaceResult;
import io.plainround.seatholds.application.simulation.SweepConfirmRace;
import io.plainround.seatholds.domain.Clock;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatStore;
```

**Lines 15 to 21**

```java
import io.plainround.seatholds.domain.SeatTransitions;
import io.plainround.seatholds.infrastructure.memory.PlainWriteSeatStore;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
```

**Lines 23 to 30**

```java
/** The lesson's scenarios on whichever store is configured, then the failure they guard against. */
final class Scenario {

  private static final int RACE_RUNS = 5;
  private static final int CUSTOMERS = 1_000;
  private static final int SEATS = 10;
  private static final int SWEEP_REPETITIONS = 100;
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
```

**Lines 32 to 42**

```java
  private final SeatStore store;
  private final SeatHoldService service;
  private final String runId;
  private final PrintStream out;

  Scenario(SeatStore store, SeatHoldService service, String runId, PrintStream out) {
    this.store = store;
    this.service = service;
    this.runId = runId;
    this.out = out;
  }
```

**Lines 44 to 56**

```java
  /**
   * Runs every scenario.
   *
   * @return true if every invariant held
   */
  boolean run() {
    confirmingAnExpiredHoldGivesAReason();
    staleUpdateIsRejected();
    boolean racesHeld = racesHaveExactlyTenWinners();
    boolean sweepHeld = sweepVersusConfirm();
    plainWriteSellsTwice();
    return racesHeld && sweepHeld;
  }
```

**Lines 58 to 63**

```java
  private void confirmingAnExpiredHoldGivesAReason() {
    Clock fixed = () -> Instant.parse("2026-01-01T00:00:00Z");
    var expiredHold = new Held(new CustomerId("ada"), fixed.now().minus(Duration.ofMinutes(1)));
    Outcome result = SeatTransitions.confirm(expiredHold, new CustomerId("ada"), fixed);
    out.println("confirm expired hold -> " + result + "  (never Success)");
  }
```

**Lines 65 to 72**

```java
  private void staleUpdateIsRejected() {
    SeatId seat = new SeatId("demo-" + runId);
    store.add(seat);
    Instant expiry = Instant.now().plus(TEN_MINUTES);
    boolean first = store.update(seat, new Free(), new Held(new CustomerId("ada"), expiry));
    boolean second = store.update(seat, new Free(), new Held(new CustomerId("bruno"), expiry));
    out.println("first update: " + first + "  second update (stale expected): " + second);
  }
```

**Lines 74 to 83**

```java
  private boolean racesHaveExactlyTenWinners() {
    boolean held = true;
    for (int run = 1; run <= RACE_RUNS; run++) {
      List<SeatId> seats = freshSeats("race-" + run);
      RaceResult result = new HoldRace(service).run(seats, CUSTOMERS);
      out.println("race result: " + result);
      held &= result.wins() == SEATS && result.losses() == CUSTOMERS - SEATS;
    }
    return held;
  }
```

**Lines 85 to 97**

```java
  private boolean sweepVersusConfirm() {
    out.println(
        "-- a confirm and the expiry sweep meet on one seat, " + SWEEP_REPETITIONS + " times --");
    var race = new SweepConfirmRace(store);
    int sold = 0;
    int freed = 0;
    int inconsistent = 0;
    for (int rep = 1; rep <= SWEEP_REPETITIONS; rep++) {
      var result = race.run(new SeatId("sweep-" + runId + "-" + rep));
      if (!result.isConsistent()) {
        inconsistent++;
      } else if (result.finalState() instanceof SeatState.Sold) {
        sold++;
```

**Lines 98 to 111**

```java
      } else {
        freed++;
      }
      if (rep <= 3 || rep == SWEEP_REPETITIONS) {
        out.println(String.format("rep %-3d  seat ended as %s", rep, result.finalState()));
      } else if (rep == 4) {
        out.println("...");
      }
    }
    out.println(
        "sold="
            + sold
            + " free="
            + freed
```

**Lines 112 to 125**

```java
            + " inconsistent="
            + inconsistent
            + "  (every repetition: exactly one of Sold or Free, never both, never a third state)");
    return inconsistent == 0;
  }

  private void plainWriteSellsTwice() {
    out.println("-- the failure: a plain write instead of compare-and-set --");
    var plain = new PlainWriteSeatStore();
    SeatId seat = new SeatId("s-9");
    plain.add(seat);
    SeatState adaRead = plain.get(seat);
    SeatState brunoRead = plain.get(seat);
    Instant expiry = Instant.now().plus(TEN_MINUTES);
```

**Lines 126 to 135**

```java
    boolean ada = plain.update(seat, adaRead, new Held(new CustomerId("ada"), expiry));
    boolean bruno = plain.update(seat, brunoRead, new Held(new CustomerId("bruno"), expiry));
    out.println(
        "first update: "
            + ada
            + "  second update (stale expected): "
            + bruno
            + "  <- both were told they won seat s-9; it now belongs to "
            + plain.get(seat));
  }
```

**Lines 137 to 145**

```java
  private List<SeatId> freshSeats(String label) {
    List<SeatId> seats =
        IntStream.rangeClosed(1, SEATS)
            .mapToObj(n -> new SeatId(label + "-" + runId + "-" + n))
            .toList();
    seats.forEach(store::add);
    return seats;
  }
}
```

| Lines | What they do |
| --- | --- |
| 3-21 | Imports the service, the race drivers, the domain types, the counter-example store and standard-library types. |
| 24 | A package-private `final` class that runs the lesson's scenarios. |
| 26-30 | Constants: 5 race runs, 1,000 customers, 10 seats, 100 sweep repetitions, a ten-minute `Duration`. Named constants instead of bare numbers. |
| 32-42 | Fields and constructor: the store, the service, the run id and the output stream. |
| 49-56 | `run()` calls the five steps in order and returns `true` only if both checks that can fail (the races and the sweep race) held. |
| 58-63 | `confirmingAnExpiredHoldGivesAReason`: `Clock fixed = () -> Instant.parse("2026-01-01T00:00:00Z")` is a lambda that **is** a clock fixed at one instant. The hold expired one minute before it (`fixed.now().minus(Duration.ofMinutes(1))`). `SeatTransitions.confirm(...)` is asked, and the line printed shows `Reason[message=hold expired]`. |
| 65-72 | `staleUpdateIsRejected`: adds a fresh seat, then tries two updates that both expect `Free`. The first (ada) returns `true`; the second (bruno), whose expected state is now stale, returns `false`. |
| 74-83 | `racesHaveExactlyTenWinners`: for each of five runs, makes ten fresh seats (`freshSeats`), runs the thousand-thread race, prints the result and checks `wins == 10 && losses == 990` (`held &= ...`). |
| 85-116 | `sweepVersusConfirm`: prints a heading, creates the race driver, and for each of 100 repetitions runs it on a fresh seat. `result.isConsistent()` is false only for an impossible outcome (counted as `inconsistent`); otherwise the final state is `Sold` (counted in `sold`) or `Free` (in `freed`). Lines 101-105 print the first three repetitions, an ellipsis and the last: `String.format("rep %-3d  seat ended as %s", ...)` pads the number to width 3, left-aligned. Lines 107-114 print the totals. |
| 118-135 | `plainWriteSellsTwice`: uses the counter-example store. Two customers both **read** the seat (123-124) before either **writes**; both writes succeed (126-127); the line shows both `true` and who owns the seat now: the seat sold twice. |
| 137-144 | `freshSeats(label)`: makes ten seat ids with the label and the run id (`IntStream.rangeClosed(1, SEATS).mapToObj(...)`), adds them to the store (`store::add`) and returns the list. |

## Try it yourself

1. `make run` prints the scenarios on the in-memory store.
2. `java -cp "target/classes:target/lib/*" io.plainround.seatholds.interfaces.cli.Main hold s-9 ada` then `... status` (build first with `make build`): each process starts with fresh in-memory seats, so `status` shows `Free` again (with `STORE=jdbc` the state persists).
3. `jshell --class-path target/classes` then `import io.plainround.seatholds.domain.*;` and `new SeatState.Held(new CustomerId("ada"), java.time.Instant.parse("2026-01-01T00:10:00.123456789Z"))` prints an expiry of `...00.123Z`: truncated to milliseconds.
4. In `jshell`: `SeatTransitions.confirm(new SeatState.Free(), new CustomerId("ada"), java.time.Instant::now)`. (`Clock` is a one-method interface; the method reference works as one.) It returns `Reason[message=not held]`.
5. `javap -cp target/classes io.plainround.seatholds.domain.SeatStore` shows the four methods of the port.

## Mistakes people make

- **A mutable `Seat` object with a `hold()` method** that needs a store and a clock to test. Here states are data and transitions are pure functions.
- **Locking inside the domain** and calling it the guarantee. The guarantee is `SeatStore.update`; an ArchUnit rule keeps `java.util.concurrent` out of the domain.
- **A `default` branch** on a sealed `switch`, hiding new states.
- **Retrying the same write after a lost race** instead of reading and deciding again. The state may have changed what is right.
- **Treating `Instant` equality loosely.** Nanoseconds differ between systems; `Held` truncates to milliseconds so every adapter agrees.

## Check yourself

1. *Why are the transitions pure functions?* They depend only on their arguments, so they are testable with a value and a fixed clock.
2. *What does `SeatStore.update` return, and when?* `true` only if the seat was in the expected state and the new state was applied; otherwise `false`.
3. *What happens when `update` returns `false` in `decideAndWrite`?* It reads the state again and decides again, up to five attempts, then returns a "contended" reason.
4. *Why does `Held` truncate the expiry to milliseconds?* So the value written to a store of any precision reads back equal.
5. *Why is `Clock` an interface?* So tests can supply a fixed clock instead of the real time.

## Further reading

- The Java Language Specification, Java SE 21: <https://docs.oracle.com/javase/specs/jls/se21/html/index.html>
- Records and sealed types: <https://docs.oracle.com/en/java/javase/21/language/records.html>, <https://docs.oracle.com/en/java/javase/21/language/sealed-classes-and-interfaces.html>
- Pattern matching for `switch` (with guards): <https://docs.oracle.com/en/java/javase/21/language/pattern-matching-switch-expressions-and-statements.html>
- `java.time`: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/package-summary.html>
