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
package io.plainround.seatholds.domain;

/**
 * Identifier of a seat; never blank.
 *
 * @param value the id, such as {@code s-9}
 */
public record SeatId(String value) {

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

  @Override
  public String toString() {
    return value;
  }
}
```

A record around a `String` whose compact constructor rejects `null` or blank and trims spaces. `CustomerId` is the same shape. Two different types stop a customer id being passed where a seat id belongs. `toString` returns just the text so logs read `s-9`.

### `domain/SeatState.java`: three states as data

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

  /**
   * The seat is sold.
   *
   * @param customer who bought it
   */
  record Sold(CustomerId customer) implements SeatState {

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

- `Free` has no data. `Held(CustomerId customer, Instant expiresAt)` has a holder and an expiry. `Sold(CustomerId customer)` has a buyer. Each record's constructor refuses missing parts.
- `Held`'s constructor does `expiresAt = expiresAt.truncatedTo(ChronoUnit.MILLIS)`: it keeps only milliseconds, so a value written to a database column of any precision reads back **equal** (`Instant` has nanoseconds; PostgreSQL stores microseconds).
- These are only **data**. The rules of what may follow what are not here but in `SeatTransitions`.

### `domain/Outcome.java`

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

  /**
   * The transition is refused.
   *
   * @param message why, in words a caller can show
   */
  record Reason(String message) implements Outcome {}
}
```

What a transition answers: `Success(SeatState newState)` or `Reason(String message)`. Sealed, so callers must handle both. A refusal is a normal answer, not an exception.

### `domain/SeatTransitions.java`: pure functions with guarded patterns

```java
package io.plainround.seatholds.domain;

import io.plainround.seatholds.domain.Outcome.Reason;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import java.time.Duration;

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
    return switch (current) {
      case Free free -> new Success(new Held(customer, clock.now().plus(holdFor)));
      case Held held -> new Reason("seat is held");
      case Sold sold -> new Reason("already sold");
    };
  }

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
      case Held held when held.customer().equals(customer) -> new Reason("hold expired");
      case Held held -> new Reason("held by someone else");
      case Free free -> new Reason("not held");
      case Sold sold -> new Reason("already sold");
    };
  }

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
    };
  }
}
```

- The class is `final` with a private constructor: only static methods, never instantiated.
- **Pure** means: the answer depends only on the arguments, and nothing outside changes. No store, no thread, no exception.
- `hold`: `case Free free -> new Success(new Held(customer, clock.now().plus(holdFor)))`; a held or sold seat gives a `Reason`.
- `confirm`: `case Held held when held.customer().equals(customer) && !clock.now().isAfter(held.expiresAt())` is a guarded pattern: this customer, and not yet expired, gives `Success(new Sold(customer))`. The next case, the same holder but after the expiry, gives `Reason("hold expired")`. Any other holder gives "held by someone else". `Free` gives "not held". `Sold` gives "already sold". The order of the cases matters: the first match wins.
- `release`: only `Held` **after** its expiry (`clock.now().isAfter(...)`) becomes `Free`. The boundary is on purpose complementary to `confirm`: at exactly the expiry instant a confirm succeeds and a release is refused, so at any instant exactly one of the two is allowed.
- No `default` anywhere: each `switch` covers the three sealed states, and the compiler proves it.

### `domain/Clock.java`, `SeatStore.java`, the exceptions: the ports

```java
package io.plainround.seatholds.domain;

import java.time.Instant;

/** Outbound port: the current time, injected so an expired hold can be tested without waiting. */
@FunctionalInterface
public interface Clock {

  /**
   * The current instant.
   *
   * @return now
   */
  Instant now();
}
```

A one-method interface: the current time. Injecting it lets a test fix time (`MutableClock`), so an expired hold is tested without waiting.

```java
package io.plainround.seatholds.domain;

import java.util.List;

/**
 * Outbound port: where seat states live. This is where "a seat is sold at most once" is enforced:
 * {@link #update} is a compare-and-set, so a write based on stale data cannot win. Every adapter
 * must pass the same contract test.
 */
public interface SeatStore {

  /**
   * Reads a seat's current state.
   *
   * @param seat the seat
   * @return its state
   * @throws UnknownSeatException if the seat was never added
   */
  SeatState get(SeatId seat);

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

  /**
   * Adds a seat in the free state unless it already exists.
   *
   * @param seat the seat
   * @return true if it was added, false if it was already there
   */
  boolean add(SeatId seat);

  /**
   * Every seat the store knows, in id order.
   *
   * @return an immutable list
   */
  List<SeatId> seats();
}
```

The most important interface in the repository. `get` reads a state. `update(seat, expected, next)` is a **compare-and-set**: it writes `next` only if the seat is still in `expected`, and returns whether it did. `add` creates a free seat if absent; `seats` lists the ids. The Javadoc says it: this is where "sold at most once" is guaranteed. Every adapter must pass the same contract test (`junit.md`).

```java
public final class UnknownSeatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param seat the unknown seat
   */
  public UnknownSeatException(SeatId seat) {
    super("unknown seat: " + seat);
```
```java
public final class SeatStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the store was asked to do
   * @param cause the underlying failure
   */
  public SeatStoreException(String message, Throwable cause) {
    super(message, cause);
```

Two named errors: a seat the store does not know (a caller mistake) and a store that could not answer (the cause, such as a database error, is kept with `super(message, cause)`).

### `application/`: the inbound ports and the service

```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: a customer wants a seat held. Called by an adapter; never calls out to one. */
public interface HoldSeats {

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
```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: a customer turns their hold into a sale. */
public interface ConfirmHold {

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
```java
package io.plainround.seatholds.application;

import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;

/** Inbound port: free holds whose time has run out. Called by a scheduler. */
public interface ExpireHolds {

  /**
   * Sweeps all seats once.
   *
   * @return how many holds were released
   */
  int sweep();

  /**
   * Releases one seat's hold if, and only if, it has expired.
   *
   * @param seat the seat
   * @return the freed seat, or why it was left alone
   */
  Outcome release(SeatId seat);
}
```

Three small interfaces, each naming one thing a caller wants: hold a seat, confirm a hold, expire holds. They are called **inbound ports** because callers (the command line, a test, later an HTTP handler) call into them.

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
import java.util.function.Function;

/**
 * The use cases. Each reads the seat, asks a pure transition what should happen, and writes the
 * result through the store's compare-and-set. If the write loses a race, it reads again and decides
 * again; a state that changed under it may make a different transition right, or none.
 */
public final class SeatHoldService implements HoldSeats, ConfirmHold, ExpireHolds {

  private static final Logger LOG = System.getLogger(SeatHoldService.class.getName());
  private static final int MAX_ATTEMPTS = 5;

  private final SeatStore store;
  private final Clock clock;
  private final Duration holdFor;

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

  @Override
  public Outcome hold(SeatId seat, CustomerId customer) {
    Outcome outcome =
        decideAndWrite(seat, current -> SeatTransitions.hold(current, customer, clock, holdFor));
    log("hold", seat, customer, outcome);
    return outcome;
  }

  @Override
  public Outcome confirm(SeatId seat, CustomerId customer) {
    Outcome outcome =
        decideAndWrite(seat, current -> SeatTransitions.confirm(current, customer, clock));
    log("confirm", seat, customer, outcome);
    return outcome;
  }

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

  @Override
  public Outcome release(SeatId seat) {
    Outcome outcome = decideAndWrite(seat, current -> SeatTransitions.release(current, clock));
    if (outcome instanceof Success) {
      LOG.log(Level.INFO, "event=released seat={0}", seat);
    }
    return outcome;
  }

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
  }
}
```

- `SeatHoldService implements HoldSeats, ConfirmHold, ExpireHolds`: one class, three interfaces. It receives the store, the clock and the hold length in its constructor.
- `hold` and `confirm` call `decideAndWrite(seat, transition)`, passing a **lambda** that makes the decision for the state it is given: `current -> SeatTransitions.hold(current, customer, clock, holdFor)`.
- `decideAndWrite` is the loop at the heart: read the state (`store.get`), decide (`transition.apply(current)`); if the outcome is a `Reason`, return it; otherwise try `store.update(seat, current, success.newState())`. If that returns `true`, done. If `false`, someone changed the seat in between, so **read and decide again**, at most `MAX_ATTEMPTS` (5) times, then answer `Reason("seat is contended, try again")`. `!(outcome instanceof Success success)` is a pattern: if it is not a `Success`, return it; otherwise `success` names the value.
- `sweep` walks every seat and releases expired holds through the same method; `release(seat)` does one seat.
- `log` writes `INFO` for a success and `DEBUG` for a refusal (`java-logging.md`).

### What other files cover

- `application/simulation/` (the race drivers), `infrastructure/memory/` (the in-memory store and the counter-example), `infrastructure/scheduling/ScheduledSweeper.java` and `interfaces/cli/Main.java` (the shutdown hook): `java-concurrency.md`.
- `infrastructure/jdbc/` and `sql/`: `jdbc.md`, `hikaricp.md`, `sql.md`, `postgresql.md`.
- `infrastructure/time/SystemClock.java` is one method, `return Instant.now();`, the real clock.
- `interfaces/cli/Logging.java`, `ConfigurationException.java`, `AppConfig.java`: `java-logging.md`, `environment-variables.md`.

### `interfaces/cli/Application.java`: the composition root

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

/** The composition root: builds the chosen store and the service, and closes what it opened. */
final class Application implements AutoCloseable {

  private final AutoCloseable resource;
  private final SeatStore store;
  private final SeatHoldService service;
  private final List<SeatId> seatIds;

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

  SeatStore store() {
    return store;
  }

  SeatHoldService service() {
    return service;
  }

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

The one place that **chooses and creates** the real objects. It implements `AutoCloseable` so it can be used in a try-with-resources and closes what it opened. For `STORE=jdbc` it opens a `JdbcSeatStore` (which owns its connection pool), otherwise it creates the in-memory store. It adds seats `s-1`..`s-N` through the port (`seatIds.forEach(store::add)`: `store::add` is a method reference), then builds the service with the system clock. `close` closes whatever `resource` holds; `() -> {}` is a lambda that does nothing, used when there is nothing to close.

### `interfaces/cli/Cli.java` and `Scenario.java`

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

/** Command-line driver: parses arguments, calls the use cases, prints results. */
public final class Cli {

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
      """;

  private final Map<String, String> env;
  private final PrintStream out;
  private final PrintStream err;
  private final CountDownLatch shutdown;

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
      err.println(problem.getMessage());
      return 1;
    }
  }

  private int scenario(Application app) {
    String runId = Long.toString(Instant.now().toEpochMilli(), 36);
    boolean held = new Scenario(app.store(), app.service(), runId, out).run();
    return held ? 0 : 1;
  }

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
  }
}
```

- The constructor receives environment, output streams and a `CountDownLatch` (`java-concurrency.md`) standing for the shutdown signal.
- `run` returns an exit code: it handles `help`, validates settings, then runs the command inside `try (Application app = new Application(config))` so the store is closed whatever happens. `catch (SeatStoreException ...)` prints "store unavailable" and returns 1; `IllegalArgumentException` returns 2.
- `dispatch` is a `switch` expression over the command word. `hold`, `confirm` pass lambdas to a helper (`twoArguments(args, (seat, customer) -> ...)`): `SeatAction` is a private functional interface. `print(outcome)` turns a result into output and an exit code (0 for `Success`, 1 for a refusal).
- `race` builds ten fresh seats per run with `IntStream.rangeClosed(1, 10).mapToObj(...).toList()`: a stream of numbers turned into `SeatId`s.

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
import io.plainround.seatholds.domain.SeatTransitions;
import io.plainround.seatholds.infrastructure.memory.PlainWriteSeatStore;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** The lesson's scenarios on whichever store is configured, then the failure they guard against. */
final class Scenario {

  private static final int RACE_RUNS = 5;
  private static final int CUSTOMERS = 1_000;
  private static final int SEATS = 10;
  private static final int SWEEP_REPETITIONS = 100;
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

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

  private void confirmingAnExpiredHoldGivesAReason() {
    Clock fixed = () -> Instant.parse("2026-01-01T00:00:00Z");
    var expiredHold = new Held(new CustomerId("ada"), fixed.now().minus(Duration.ofMinutes(1)));
    Outcome result = SeatTransitions.confirm(expiredHold, new CustomerId("ada"), fixed);
    out.println("confirm expired hold -> " + result + "  (never Success)");
  }

  private void staleUpdateIsRejected() {
    SeatId seat = new SeatId("demo-" + runId);
    store.add(seat);
    Instant expiry = Instant.now().plus(TEN_MINUTES);
    boolean first = store.update(seat, new Free(), new Held(new CustomerId("ada"), expiry));
    boolean second = store.update(seat, new Free(), new Held(new CustomerId("bruno"), expiry));
    out.println("first update: " + first + "  second update (stale expected): " + second);
  }

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

`Scenario` prints the lesson's scenarios in the lesson's order (expired confirm, stale update, five races, the 100 sweep-versus-confirm repetitions, the plain-write failure) and returns `true` only if every invariant held. `String.format("rep %-3d  seat ended as %s", ...)` pads the number to three characters (`%-3d` means left-aligned, width 3).

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
