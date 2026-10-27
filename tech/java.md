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

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  /**
 4   * Identifier of a seat; never blank.
 5   *
 6   * @param value the id, such as {@code s-9}
 7   */
 8  public record SeatId(String value) {
 9  
10    /**
11     * Creates a seat id.
12     *
13     * @throws IllegalArgumentException if the value is null or blank
14     */
15    public SeatId {
16      if (value == null || value.isBlank()) {
17        throw new IllegalArgumentException("seat id must not be blank");
18      }
19      value = value.strip();
20    }
21  
22    @Override
23    public String toString() {
24      return value;
25    }
26  }
```

| Lines | What they do |
| --- | --- |
| 1 | `package ...domain;` puts the file in the domain layer; no imports are needed. |
| 8 | `public record SeatId(String value)`: an immutable one-field type. Wrapping the text in its own type stops a customer id being passed where a seat id belongs. Java generates the constructor, the accessor `value()`, `equals`, `hashCode` and `toString`. |
| 15-20 | The **compact constructor**: it runs before the field is set. Lines 16-18 refuse `null` or blank text (`isBlank()` is true for empty text or spaces only). Line 19 trims the text (`value = value.strip();`), so `new SeatId(" s-9 ")` equals `new SeatId("s-9")`. |
| 22-25 | `@Override` (the compiler checks it replaces an inherited method) `toString` returns the plain text, so logs print `s-9`. |

`CustomerId` is identical line for line with `customer id` in the messages: a record around text, a compact constructor that rejects blank text and trims, and a `toString` that returns the text.

### `domain/SeatState.java`: three states as data

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  import java.time.Instant;
 4  import java.time.temporal.ChronoUnit;
 5  
 6  /**
 7   * Where a seat is in its life: free, held for one customer until a time, or sold. Data, not an
 8   * object with behavior: the transitions are pure functions in {@link SeatTransitions}, and the
 9   * guarantee that a seat is sold at most once belongs to {@link SeatStore#update}, not to these
10   * records.
11   */
12  public sealed interface SeatState permits SeatState.Free, SeatState.Held, SeatState.Sold {
13  
14    /** Nobody holds the seat and it has not been sold. */
15    record Free() implements SeatState {}
16  
17    /**
18     * One customer has the seat for a limited time.
19     *
20     * @param customer who holds it
21     * @param expiresAt the last instant the hold can be confirmed, kept to the millisecond so every
22     *     store, whatever its timestamp precision, compares equal values as equal
23     */
24    record Held(CustomerId customer, Instant expiresAt) implements SeatState {
25  
26      /**
27       * Normalizes the expiry to milliseconds.
28       *
29       * @throws IllegalArgumentException if the customer or the expiry is missing
30       */
31      public Held {
32        if (customer == null || expiresAt == null) {
33          throw new IllegalArgumentException("a hold needs a customer and an expiry");
34        }
35        expiresAt = expiresAt.truncatedTo(ChronoUnit.MILLIS);
36      }
37    }
38  
39    /**
40     * The seat is sold.
41     *
42     * @param customer who bought it
43     */
44    record Sold(CustomerId customer) implements SeatState {
45  
46      /**
47       * Validates the buyer.
48       *
49       * @throws IllegalArgumentException if the customer is missing
50       */
51      public Sold {
52        if (customer == null) {
53          throw new IllegalArgumentException("a sale needs a customer");
54        }
55      }
56    }
57  }
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

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  /** What a transition decides: the next state, or a reason there is none. Never an exception. */
 4  public sealed interface Outcome permits Outcome.Success, Outcome.Reason {
 5  
 6    /**
 7     * The transition is allowed.
 8     *
 9     * @param newState the state the seat should move to
10     */
11    record Success(SeatState newState) implements Outcome {}
12  
13    /**
14     * The transition is refused.
15     *
16     * @param message why, in words a caller can show
17     */
18    record Reason(String message) implements Outcome {}
19  }
```

Line 4 declares another sealed interface with exactly two cases. Line 11, `record Success(SeatState newState)`, is the answer "allowed: move the seat to this state". Line 18, `record Reason(String message)`, is "refused, and here is why". A refusal is a normal answer, not an exception: callers must handle both because the interface is sealed.

### `domain/SeatTransitions.java`: pure functions with guarded patterns

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  import io.plainround.seatholds.domain.Outcome.Reason;
 4  import io.plainround.seatholds.domain.Outcome.Success;
 5  import io.plainround.seatholds.domain.SeatState.Free;
 6  import io.plainround.seatholds.domain.SeatState.Held;
 7  import io.plainround.seatholds.domain.SeatState.Sold;
 8  import java.time.Duration;
 9  
10  /**
11   * The seat's state machine as pure functions: each takes the current state and returns the next
12   * state or a reason. Nothing here reads a store, mutates anything or throws, so every transition is
13   * testable with a value and a fixed clock.
14   *
15   * <p>The edges are free to held (hold), held to sold (confirm, before the expiry), and held to free
16   * (release, after the expiry). The instant of the expiry itself belongs to the customer: a confirm
17   * at that instant succeeds and a release is refused, so the two cannot both be right.
18   */
19  public final class SeatTransitions {
20  
21    private SeatTransitions() {}
22  
23    /**
24     * A customer asks to hold a seat.
25     *
26     * @param current the seat's state
27     * @param customer who asks
28     * @param clock the time source
29     * @param holdFor how long the hold lasts
30     * @return the hold, or why not
31     */
32    public static Outcome hold(
33        SeatState current, CustomerId customer, Clock clock, Duration holdFor) {
34      return switch (current) {
35        case Free free -> new Success(new Held(customer, clock.now().plus(holdFor)));
36        case Held held -> new Reason("seat is held");
37        case Sold sold -> new Reason("already sold");
38      };
39    }
40  
41    /**
42     * A customer confirms the hold they were given.
43     *
44     * @param current the seat's state
45     * @param customer who confirms
46     * @param clock the time source
47     * @return the sale, or why not
48     */
49    public static Outcome confirm(SeatState current, CustomerId customer, Clock clock) {
50      return switch (current) {
51        case Held held
52            when held.customer().equals(customer) && !clock.now().isAfter(held.expiresAt()) ->
53            new Success(new Sold(customer));
54        case Held held when held.customer().equals(customer) -> new Reason("hold expired");
55        case Held held -> new Reason("held by someone else");
56        case Free free -> new Reason("not held");
57        case Sold sold -> new Reason("already sold");
58      };
59    }
60  
61    /**
62     * The sweeper frees a hold whose time has run out.
63     *
64     * @param current the seat's state
65     * @param clock the time source
66     * @return the freed seat, or why not
67     */
68    public static Outcome release(SeatState current, Clock clock) {
69      return switch (current) {
70        case Held held when clock.now().isAfter(held.expiresAt()) -> new Success(new Free());
71        case Held held -> new Reason("hold not expired");
72        case Free free -> new Reason("not held");
73        case Sold sold -> new Reason("already sold");
74      };
75    }
76  }
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

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  import java.time.Instant;
 4  
 5  /** Outbound port: the current time, injected so an expired hold can be tested without waiting. */
 6  @FunctionalInterface
 7  public interface Clock {
 8  
 9    /**
10     * The current instant.
11     *
12     * @return now
13     */
14    Instant now();
15  }
```

| Lines | What they do |
| --- | --- |
| 3 | Imports `Instant`. |
| 6 | `@FunctionalInterface`: the compiler checks there is exactly one abstract method, so a **lambda** can be a clock. |
| 7 | `public interface Clock`: the **port** for time. |
| 14 | `Instant now();` is the one method: the current moment. Injecting it lets a test fix time (`MutableClock`), so an expired hold is tested without waiting. |

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  import java.util.List;
 4  
 5  /**
 6   * Outbound port: where seat states live. This is where "a seat is sold at most once" is enforced:
 7   * {@link #update} is a compare-and-set, so a write based on stale data cannot win. Every adapter
 8   * must pass the same contract test.
 9   */
10  public interface SeatStore {
11  
12    /**
13     * Reads a seat's current state.
14     *
15     * @param seat the seat
16     * @return its state
17     * @throws UnknownSeatException if the seat was never added
18     */
19    SeatState get(SeatId seat);
20  
21    /**
22     * Moves a seat to a new state only if it is still in the state the caller read.
23     *
24     * @param seat the seat
25     * @param expected the state the caller based its decision on
26     * @param next the state to move to
27     * @return true only if the seat was in {@code expected} and {@code next} was applied; false if
28     *     someone else changed it first
29     * @throws UnknownSeatException if the seat was never added
30     */
31    boolean update(SeatId seat, SeatState expected, SeatState next);
32  
33    /**
34     * Adds a seat in the free state unless it already exists.
35     *
36     * @param seat the seat
37     * @return true if it was added, false if it was already there
38     */
39    boolean add(SeatId seat);
40  
41    /**
42     * Every seat the store knows, in id order.
43     *
44     * @return an immutable list
45     */
46    List<SeatId> seats();
47  }
```

| Lines | What they do |
| --- | --- |
| 3 | Imports `List`. |
| 10 | `public interface SeatStore`: the most important interface in the repository. Its Javadoc says where "a seat is sold at most once" is enforced. |
| 19 | `SeatState get(SeatId seat);` reads a state; throws `UnknownSeatException` for a seat never added. |
| 31 | `boolean update(SeatId seat, SeatState expected, SeatState next);` is a **compare-and-set**: write `next` only if the seat is still in `expected`; return `true` only if that happened, `false` if someone changed it first. |
| 39 | `boolean add(SeatId seat);` adds a free seat unless it exists; returns whether it added one. |
| 46 | `List<SeatId> seats();` lists every seat, in id order. |

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  /** The caller named a seat the store has never been given. */
 4  public final class UnknownSeatException extends RuntimeException {
 5  
 6    private static final long serialVersionUID = 1L;
 7  
 8    /**
 9     * Creates the exception.
10     *
11     * @param seat the unknown seat
12     */
13    public UnknownSeatException(SeatId seat) {
14      super("unknown seat: " + seat);
15    }
16  }
```

A named error. Line 4: a `final` class extending `RuntimeException` (unchecked). Line 6: the `serialVersionUID` constant that `-Xlint:all` asks for. Lines 13-15: the constructor takes the `SeatId` and calls `super("unknown seat: " + seat)`, storing the message in the parent class.

```java
 1  package io.plainround.seatholds.domain;
 2  
 3  /** The store could not be reached or answered with an error; the seat's state is unknown. */
 4  public final class SeatStoreException extends RuntimeException {
 5  
 6    private static final long serialVersionUID = 1L;
 7  
 8    /**
 9     * Creates the exception.
10     *
11     * @param message what the store was asked to do
12     * @param cause the underlying failure
13     */
14    public SeatStoreException(String message, Throwable cause) {
15      super(message, cause);
16    }
17  }
```

The same, with a **cause**: the constructor (lines 14-16) takes a message and the underlying `Throwable` and calls `super(message, cause)`, so the original database error is kept and shown in the stack trace. It means "the store could not be reached or answered with an error", so the seat's state is unknown.

### `application/`: the inbound ports and the service

```java
 1  package io.plainround.seatholds.application;
 2  
 3  import io.plainround.seatholds.domain.CustomerId;
 4  import io.plainround.seatholds.domain.Outcome;
 5  import io.plainround.seatholds.domain.SeatId;
 6  
 7  /** Inbound port: a customer wants a seat held. Called by an adapter; never calls out to one. */
 8  public interface HoldSeats {
 9  
10    /**
11     * Holds a seat for a customer.
12     *
13     * @param seat the seat
14     * @param customer who wants it
15     * @return the new state, or why the seat cannot be held
16     */
17    Outcome hold(SeatId seat, CustomerId customer);
18  }
```

| Lines | What they do |
| --- | --- |
| 3-5 | Imports the domain types used in the signature. |
| 8 | `public interface HoldSeats`: an **inbound port**, a thing callers (the command line, a test, later an HTTP handler) can call into. |
| 17 | `Outcome hold(SeatId seat, CustomerId customer);` has no body: it says what a caller wants (hold this seat for this customer) and what comes back (`Success` with the new state, or a `Reason`). |

`ConfirmHold` has the same shape with the method `Outcome confirm(SeatId seat, CustomerId customer)`.

```java
 1  package io.plainround.seatholds.application;
 2  
 3  import io.plainround.seatholds.domain.Outcome;
 4  import io.plainround.seatholds.domain.SeatId;
 5  
 6  /** Inbound port: free holds whose time has run out. Called by a scheduler. */
 7  public interface ExpireHolds {
 8  
 9    /**
10     * Sweeps all seats once.
11     *
12     * @return how many holds were released
13     */
14    int sweep();
15  
16    /**
17     * Releases one seat's hold if, and only if, it has expired.
18     *
19     * @param seat the seat
20     * @return the freed seat, or why it was left alone
21     */
22    Outcome release(SeatId seat);
23  }
```

Line 7 declares the third inbound port. Line 14, `int sweep();`, frees every expired hold in one pass and returns how many. Line 22, `Outcome release(SeatId seat);`, releases one seat if, and only if, its hold has expired (the race driver and `sweep` use it).

```java
  1  package io.plainround.seatholds.application;
  2  
  3  import io.plainround.seatholds.domain.Clock;
  4  import io.plainround.seatholds.domain.CustomerId;
  5  import io.plainround.seatholds.domain.Outcome;
  6  import io.plainround.seatholds.domain.Outcome.Reason;
  7  import io.plainround.seatholds.domain.Outcome.Success;
  8  import io.plainround.seatholds.domain.SeatId;
  9  import io.plainround.seatholds.domain.SeatState;
 10  import io.plainround.seatholds.domain.SeatStore;
 11  import io.plainround.seatholds.domain.SeatTransitions;
 12  import java.lang.System.Logger;
 13  import java.lang.System.Logger.Level;
 14  import java.time.Duration;
 15  import java.util.function.Function;
 16  
 17  /**
 18   * The use cases. Each reads the seat, asks a pure transition what should happen, and writes the
 19   * result through the store's compare-and-set. If the write loses a race, it reads again and decides
 20   * again; a state that changed under it may make a different transition right, or none.
 21   */
 22  public final class SeatHoldService implements HoldSeats, ConfirmHold, ExpireHolds {
 23  
 24    private static final Logger LOG = System.getLogger(SeatHoldService.class.getName());
 25    private static final int MAX_ATTEMPTS = 5;
 26  
 27    private final SeatStore store;
 28    private final Clock clock;
 29    private final Duration holdFor;
 30  
 31    /**
 32     * Creates the service.
 33     *
 34     * @param store where seats live
 35     * @param clock the time source
 36     * @param holdFor how long a hold lasts
 37     */
 38    public SeatHoldService(SeatStore store, Clock clock, Duration holdFor) {
 39      this.store = store;
 40      this.clock = clock;
 41      this.holdFor = holdFor;
 42    }
 43  
 44    @Override
 45    public Outcome hold(SeatId seat, CustomerId customer) {
 46      Outcome outcome =
 47          decideAndWrite(seat, current -> SeatTransitions.hold(current, customer, clock, holdFor));
 48      log("hold", seat, customer, outcome);
 49      return outcome;
 50    }
 51  
 52    @Override
 53    public Outcome confirm(SeatId seat, CustomerId customer) {
 54      Outcome outcome =
 55          decideAndWrite(seat, current -> SeatTransitions.confirm(current, customer, clock));
 56      log("confirm", seat, customer, outcome);
 57      return outcome;
 58    }
 59  
 60    @Override
 61    public int sweep() {
 62      int released = 0;
 63      for (SeatId seat : store.seats()) {
 64        if (release(seat) instanceof Success) {
 65          released++;
 66        }
 67      }
 68      return released;
 69    }
 70  
 71    @Override
 72    public Outcome release(SeatId seat) {
 73      Outcome outcome = decideAndWrite(seat, current -> SeatTransitions.release(current, clock));
 74      if (outcome instanceof Success) {
 75        LOG.log(Level.INFO, "event=released seat={0}", seat);
 76      }
 77      return outcome;
 78    }
 79  
 80    private Outcome decideAndWrite(SeatId seat, Function<SeatState, Outcome> transition) {
 81      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
 82        SeatState current = store.get(seat);
 83        Outcome outcome = transition.apply(current);
 84        if (!(outcome instanceof Success success)) {
 85          return outcome;
 86        }
 87        if (store.update(seat, current, success.newState())) {
 88          return outcome;
 89        }
 90      }
 91      return new Reason("seat is contended, try again");
 92    }
 93  
 94    /** Successes are INFO; a refusal is routine under contention, so it is DEBUG (FINE). */
 95    private static void log(String action, SeatId seat, CustomerId customer, Outcome outcome) {
 96      if (outcome instanceof Reason reason) {
 97        LOG.log(
 98            Level.DEBUG,
 99            "event={0} seat={1} customer={2} outcome=refused reason=\"{3}\"",
100            action,
101            seat,
102            customer,
103            reason.message());
104      } else {
105        LOG.log(Level.INFO, "event={0} seat={1} customer={2} outcome=ok", action, seat, customer);
106      }
107    }
108  }
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
- `infrastructure/time/SystemClock.java` is one method, `return Instant.now();`, the real clock.
- `interfaces/cli/Logging.java`, `ConfigurationException.java`, `AppConfig.java`: `java-logging.md`, `environment-variables.md`.

### `interfaces/cli/Application.java`: the composition root

```java
 1  package io.plainround.seatholds.interfaces.cli;
 2  
 3  import io.plainround.seatholds.application.SeatHoldService;
 4  import io.plainround.seatholds.domain.SeatId;
 5  import io.plainround.seatholds.domain.SeatStore;
 6  import io.plainround.seatholds.domain.SeatStoreException;
 7  import io.plainround.seatholds.infrastructure.jdbc.JdbcSeatStore;
 8  import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
 9  import io.plainround.seatholds.infrastructure.time.SystemClock;
10  import java.util.List;
11  import java.util.stream.IntStream;
12  
13  /** The composition root: builds the chosen store and the service, and closes what it opened. */
14  final class Application implements AutoCloseable {
15  
16    private final AutoCloseable resource;
17    private final SeatStore store;
18    private final SeatHoldService service;
19    private final List<SeatId> seatIds;
20  
21    Application(AppConfig config) {
22      if (config.store() == AppConfig.StoreKind.JDBC) {
23        JdbcSeatStore jdbc = JdbcSeatStore.open(config.database());
24        store = jdbc;
25        resource = jdbc;
26      } else {
27        store = new InMemorySeatStore();
28        resource = () -> {};
29      }
30      seatIds = IntStream.rangeClosed(1, config.seats()).mapToObj(n -> new SeatId("s-" + n)).toList();
31      seatIds.forEach(store::add);
32      service = new SeatHoldService(store, new SystemClock(), config.holdFor());
33    }
34  
35    SeatStore store() {
36      return store;
37    }
38  
39    SeatHoldService service() {
40      return service;
41    }
42  
43    List<SeatId> seatIds() {
44      return seatIds;
45    }
46  
47    @Override
48    public void close() {
49      try {
50        resource.close();
51      } catch (Exception failure) {
52        throw new SeatStoreException("closing the store", failure);
53      }
54    }
55  }
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

```java
 1  package io.plainround.seatholds.interfaces.cli;
 2  
 3  import io.plainround.seatholds.infrastructure.jdbc.DatabaseSettings;
 4  import java.time.Duration;
 5  import java.time.format.DateTimeParseException;
 6  import java.util.Locale;
 7  import java.util.Map;
 8  
 9  /**
10   * Settings read from the environment and validated once at start-up; see {@code .env.example}.
11   *
12   * @param store which adapter holds the seats
13   * @param database where the database is; present only for the JDBC store
14   * @param holdFor how long a hold lasts
15   * @param sweepEvery the delay between expiry sweeps
16   * @param seats how many seats {@code s-1} to {@code s-N} the store is given at start-up
17   * @param logLevel the lowest log level written to standard error
18   */
19  public record AppConfig(
20      StoreKind store,
21      DatabaseSettings database,
22      Duration holdFor,
23      Duration sweepEvery,
24      int seats,
25      String logLevel) {
26  
27    /** The seat store adapters. */
28    public enum StoreKind {
29      /** A concurrent map in this process. */
30      MEMORY,
31      /** PostgreSQL through JDBC. */
32      JDBC
33    }
34  
35    /**
36     * Reads and validates the settings.
37     *
38     * @param env the environment variables
39     * @return the validated settings
40     * @throws ConfigurationException if a value is malformed or out of range
41     */
42    public static AppConfig from(Map<String, String> env) {
43      try {
44        StoreKind store =
45            StoreKind.valueOf(env.getOrDefault("STORE", "memory").toUpperCase(Locale.ROOT));
46        DatabaseSettings database =
47            store == StoreKind.JDBC
48                ? new DatabaseSettings(
49                    env.get("DATABASE_URL"),
50                    env.get("DATABASE_USER"),
51                    env.get("DATABASE_PASSWORD"),
52                    Integer.parseInt(env.getOrDefault("POOL_SIZE", "16")),
53                    positive("CONNECTION_TIMEOUT", env.getOrDefault("CONNECTION_TIMEOUT", "PT30S")))
54                : null;
55        Duration holdFor = positive("HOLD_DURATION", env.getOrDefault("HOLD_DURATION", "PT10M"));
56        Duration sweepEvery = positive("SWEEP_INTERVAL", env.getOrDefault("SWEEP_INTERVAL", "PT30S"));
57        int seats = Integer.parseInt(env.getOrDefault("SEATS", "10"));
58        if (seats < 1) {
59          throw new IllegalArgumentException("SEATS must be at least 1, was " + seats);
60        }
61        String level = env.getOrDefault("LOG_LEVEL", "INFO").toUpperCase(Locale.ROOT);
62        Logging.requireKnownLevel(level);
63        return new AppConfig(store, database, holdFor, sweepEvery, seats, level);
64      } catch (DateTimeParseException | IllegalArgumentException problem) {
65        throw new ConfigurationException("invalid configuration: " + problem.getMessage());
66      }
67    }
68  
69    private static Duration positive(String name, String text) {
70      Duration duration = Duration.parse(text);
71      if (duration.isZero() || duration.isNegative()) {
72        throw new IllegalArgumentException(
73            name + " must be a positive ISO-8601 duration, was " + text);
74      }
75      return duration;
76    }
77  }
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

```java
  1  package io.plainround.seatholds.interfaces.cli;
  2  
  3  import io.plainround.seatholds.application.simulation.HoldRace;
  4  import io.plainround.seatholds.application.simulation.RaceResult;
  5  import io.plainround.seatholds.domain.CustomerId;
  6  import io.plainround.seatholds.domain.Outcome;
  7  import io.plainround.seatholds.domain.Outcome.Success;
  8  import io.plainround.seatholds.domain.SeatId;
  9  import io.plainround.seatholds.domain.SeatStoreException;
 10  import io.plainround.seatholds.domain.UnknownSeatException;
 11  import io.plainround.seatholds.infrastructure.scheduling.ScheduledSweeper;
 12  import java.io.PrintStream;
 13  import java.time.Instant;
 14  import java.util.List;
 15  import java.util.Locale;
 16  import java.util.Map;
 17  import java.util.concurrent.CountDownLatch;
 18  import java.util.stream.IntStream;
 19  
 20  /** Command-line driver: parses arguments, calls the use cases, prints results. */
 21  public final class Cli {
 22  
 23    private static final String USAGE =
 24        """
 25        usage: seatholds <command>
 26          scenario                   the lesson's scenarios on the configured store, and the failure they guard against
 27          hold <seat> <customer>     hold a seat (it lasts HOLD_DURATION)
 28          confirm <seat> <customer>  turn a hold into a sale
 29          status                     print every seat's state
 30          sweep                      free every expired hold once
 31          race [runs] [customers]    race customers for 10 fresh seats; default 5 runs of 1000
 32          serve                      sweep every SWEEP_INTERVAL until interrupted, then stop cleanly
 33          health                     check the configuration and the store, exit 0 when usable
 34          help                       print this text
 35        settings come from the environment: STORE=memory|jdbc, DATABASE_URL, DATABASE_USER, ... (see .env.example)
 36        """;
 37  
 38    private final Map<String, String> env;
 39    private final PrintStream out;
 40    private final PrintStream err;
 41    private final CountDownLatch shutdown;
 42  
 43    /**
 44     * Creates a driver.
 45     *
 46     * @param env environment variables
 47     * @param out standard output
 48     * @param err standard error
 49     * @param shutdown counted down by the process's shutdown signal; {@code serve} runs until then
 50     */
 51    public Cli(Map<String, String> env, PrintStream out, PrintStream err, CountDownLatch shutdown) {
 52      this.env = env;
 53      this.out = out;
 54      this.err = err;
 55      this.shutdown = shutdown;
 56    }
 57  
 58    /**
 59     * Runs one command.
 60     *
 61     * @param args the command line
 62     * @return the exit code: 0 on success, 1 when a check or invariant fails or the store is
 63     *     unavailable, 2 on bad usage or settings
 64     */
 65    public int run(String... args) {
 66      String command = args.length == 0 ? "help" : args[0];
 67      if (command.equals("help")) {
 68        out.print(USAGE);
 69        return 0;
 70      }
 71      AppConfig config;
 72      try {
 73        config = AppConfig.from(env);
 74      } catch (ConfigurationException problem) {
 75        err.println(problem.getMessage());
 76        return 2;
 77      }
 78      Logging.configure(config.logLevel());
 79      try (Application app = new Application(config)) {
 80        return dispatch(command, args, config, app);
 81      } catch (SeatStoreException problem) {
 82        err.println("store unavailable: " + problem.getMessage() + ": " + problem.getCause());
 83        return 1;
 84      } catch (IllegalArgumentException problem) {
 85        err.println(problem.getMessage());
 86        return 2;
 87      }
 88    }
 89  
 90    private int dispatch(String command, String[] args, AppConfig config, Application app) {
 91      try {
 92        return switch (command) {
 93          case "scenario" -> scenario(app);
 94          case "hold" ->
 95              twoArguments(args, (seat, customer) -> print(app.service().hold(seat, customer)));
 96          case "confirm" ->
 97              twoArguments(args, (seat, customer) -> print(app.service().confirm(seat, customer)));
 98          case "status" -> status(app);
 99          case "sweep" -> {
100            out.println("released " + app.service().sweep() + " expired hold(s)");
101            yield 0;
102          }
103          case "race" -> race(args, app);
104          case "serve" -> serve(config, app);
105          case "health" -> health(config, app);
106          default -> {
107            err.print(USAGE);
108            yield 2;
109          }
110        };
111      } catch (UnknownSeatException problem) {
112        err.println(problem.getMessage());
113        return 1;
114      }
115    }
116  
117    private int scenario(Application app) {
118      String runId = Long.toString(Instant.now().toEpochMilli(), 36);
119      boolean held = new Scenario(app.store(), app.service(), runId, out).run();
120      return held ? 0 : 1;
121    }
122  
123    private interface SeatAction {
124      int run(SeatId seat, CustomerId customer);
125    }
126  
127    private int twoArguments(String[] args, SeatAction action) {
128      if (args.length != 3) {
129        err.print(USAGE);
130        return 2;
131      }
132      return action.run(new SeatId(args[1]), new CustomerId(args[2]));
133    }
134  
135    private int print(Outcome outcome) {
136      out.println(outcome);
137      return outcome instanceof Success ? 0 : 1;
138    }
139  
140    private int status(Application app) {
141      for (SeatId seat : app.store().seats()) {
142        out.println(seat + " " + app.store().get(seat));
143      }
144      return 0;
145    }
146  
147    private int race(String[] args, Application app) {
148      int runs = args.length > 1 ? Integer.parseInt(args[1]) : 5;
149      int customers = args.length > 2 ? Integer.parseInt(args[2]) : 1_000;
150      String runId = Long.toString(Instant.now().toEpochMilli(), 36);
151      boolean held = true;
152      for (int run = 1; run <= runs; run++) {
153        String label = "race-" + run + "-" + runId;
154        List<SeatId> seats =
155            IntStream.rangeClosed(1, 10).mapToObj(n -> new SeatId(label + "-" + n)).toList();
156        seats.forEach(app.store()::add);
157        RaceResult result = new HoldRace(app.service()).run(seats, customers);
158        out.println("race result: " + result);
159        held &= result.wins() == Math.min(10, customers);
160      }
161      return held ? 0 : 1;
162    }
163  
164    private int serve(AppConfig config, Application app) {
165      ScheduledSweeper sweeper = new ScheduledSweeper(app.service(), config.sweepEvery());
166      try {
167        out.println(
168            "serving: sweeping expired holds every " + config.sweepEvery() + "; Ctrl-C to stop");
169        shutdown.await();
170        out.println("stopping: finishing the sweep in progress");
171        return 0;
172      } catch (InterruptedException interrupted) {
173        Thread.currentThread().interrupt();
174        return 1;
175      } finally {
176        sweeper.close();
177      }
178    }
179  
180    private int health(AppConfig config, Application app) {
181      app.store().seats();
182      out.println(
183          "ok store="
184              + config.store().name().toLowerCase(Locale.ROOT)
185              + " seats="
186              + app.seatIds().size());
187      return 0;
188    }
189  }
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

```java
  1  package io.plainround.seatholds.interfaces.cli;
  2  
  3  import io.plainround.seatholds.application.SeatHoldService;
  4  import io.plainround.seatholds.application.simulation.HoldRace;
  5  import io.plainround.seatholds.application.simulation.RaceResult;
  6  import io.plainround.seatholds.application.simulation.SweepConfirmRace;
  7  import io.plainround.seatholds.domain.Clock;
  8  import io.plainround.seatholds.domain.CustomerId;
  9  import io.plainround.seatholds.domain.Outcome;
 10  import io.plainround.seatholds.domain.SeatId;
 11  import io.plainround.seatholds.domain.SeatState;
 12  import io.plainround.seatholds.domain.SeatState.Free;
 13  import io.plainround.seatholds.domain.SeatState.Held;
 14  import io.plainround.seatholds.domain.SeatStore;
 15  import io.plainround.seatholds.domain.SeatTransitions;
 16  import io.plainround.seatholds.infrastructure.memory.PlainWriteSeatStore;
 17  import java.io.PrintStream;
 18  import java.time.Duration;
 19  import java.time.Instant;
 20  import java.util.List;
 21  import java.util.stream.IntStream;
 22  
 23  /** The lesson's scenarios on whichever store is configured, then the failure they guard against. */
 24  final class Scenario {
 25  
 26    private static final int RACE_RUNS = 5;
 27    private static final int CUSTOMERS = 1_000;
 28    private static final int SEATS = 10;
 29    private static final int SWEEP_REPETITIONS = 100;
 30    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
 31  
 32    private final SeatStore store;
 33    private final SeatHoldService service;
 34    private final String runId;
 35    private final PrintStream out;
 36  
 37    Scenario(SeatStore store, SeatHoldService service, String runId, PrintStream out) {
 38      this.store = store;
 39      this.service = service;
 40      this.runId = runId;
 41      this.out = out;
 42    }
 43  
 44    /**
 45     * Runs every scenario.
 46     *
 47     * @return true if every invariant held
 48     */
 49    boolean run() {
 50      confirmingAnExpiredHoldGivesAReason();
 51      staleUpdateIsRejected();
 52      boolean racesHeld = racesHaveExactlyTenWinners();
 53      boolean sweepHeld = sweepVersusConfirm();
 54      plainWriteSellsTwice();
 55      return racesHeld && sweepHeld;
 56    }
 57  
 58    private void confirmingAnExpiredHoldGivesAReason() {
 59      Clock fixed = () -> Instant.parse("2026-01-01T00:00:00Z");
 60      var expiredHold = new Held(new CustomerId("ada"), fixed.now().minus(Duration.ofMinutes(1)));
 61      Outcome result = SeatTransitions.confirm(expiredHold, new CustomerId("ada"), fixed);
 62      out.println("confirm expired hold -> " + result + "  (never Success)");
 63    }
 64  
 65    private void staleUpdateIsRejected() {
 66      SeatId seat = new SeatId("demo-" + runId);
 67      store.add(seat);
 68      Instant expiry = Instant.now().plus(TEN_MINUTES);
 69      boolean first = store.update(seat, new Free(), new Held(new CustomerId("ada"), expiry));
 70      boolean second = store.update(seat, new Free(), new Held(new CustomerId("bruno"), expiry));
 71      out.println("first update: " + first + "  second update (stale expected): " + second);
 72    }
 73  
 74    private boolean racesHaveExactlyTenWinners() {
 75      boolean held = true;
 76      for (int run = 1; run <= RACE_RUNS; run++) {
 77        List<SeatId> seats = freshSeats("race-" + run);
 78        RaceResult result = new HoldRace(service).run(seats, CUSTOMERS);
 79        out.println("race result: " + result);
 80        held &= result.wins() == SEATS && result.losses() == CUSTOMERS - SEATS;
 81      }
 82      return held;
 83    }
 84  
 85    private boolean sweepVersusConfirm() {
 86      out.println(
 87          "-- a confirm and the expiry sweep meet on one seat, " + SWEEP_REPETITIONS + " times --");
 88      var race = new SweepConfirmRace(store);
 89      int sold = 0;
 90      int freed = 0;
 91      int inconsistent = 0;
 92      for (int rep = 1; rep <= SWEEP_REPETITIONS; rep++) {
 93        var result = race.run(new SeatId("sweep-" + runId + "-" + rep));
 94        if (!result.isConsistent()) {
 95          inconsistent++;
 96        } else if (result.finalState() instanceof SeatState.Sold) {
 97          sold++;
 98        } else {
 99          freed++;
100        }
101        if (rep <= 3 || rep == SWEEP_REPETITIONS) {
102          out.println(String.format("rep %-3d  seat ended as %s", rep, result.finalState()));
103        } else if (rep == 4) {
104          out.println("...");
105        }
106      }
107      out.println(
108          "sold="
109              + sold
110              + " free="
111              + freed
112              + " inconsistent="
113              + inconsistent
114              + "  (every repetition: exactly one of Sold or Free, never both, never a third state)");
115      return inconsistent == 0;
116    }
117  
118    private void plainWriteSellsTwice() {
119      out.println("-- the failure: a plain write instead of compare-and-set --");
120      var plain = new PlainWriteSeatStore();
121      SeatId seat = new SeatId("s-9");
122      plain.add(seat);
123      SeatState adaRead = plain.get(seat);
124      SeatState brunoRead = plain.get(seat);
125      Instant expiry = Instant.now().plus(TEN_MINUTES);
126      boolean ada = plain.update(seat, adaRead, new Held(new CustomerId("ada"), expiry));
127      boolean bruno = plain.update(seat, brunoRead, new Held(new CustomerId("bruno"), expiry));
128      out.println(
129          "first update: "
130              + ada
131              + "  second update (stale expected): "
132              + bruno
133              + "  <- both were told they won seat s-9; it now belongs to "
134              + plain.get(seat));
135    }
136  
137    private List<SeatId> freshSeats(String label) {
138      List<SeatId> seats =
139          IntStream.rangeClosed(1, SEATS)
140              .mapToObj(n -> new SeatId(label + "-" + runId + "-" + n))
141              .toList();
142      seats.forEach(store::add);
143      return seats;
144    }
145  }
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
