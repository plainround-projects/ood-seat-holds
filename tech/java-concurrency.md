# Java concurrency: threads, virtual threads, compare-and-set, latches, schedulers, shutdown hooks

## What it is

A **thread** is one line of execution inside a program. A program can run many threads at once, each doing its own work; on a computer with several CPU cores they can truly run at the same time. When two threads touch the same data, the result can depend on exact timing: a **race condition**. This note covers the tools Java gives to deal with it and that this repository uses: **virtual threads** (very cheap threads, new in Java 21), **atomic compare-and-set**, **latches**, **executors**, a **scheduler** and **shutdown hooks**.

A **virtual thread** is a thread managed by the JVM instead of the operating system. You can start thousands of them cheaply. When one waits (for a database answer, for example) the JVM lets another run on the same few real threads (called **carrier threads**).

## Why this repository uses it

The lesson's hard part: "A thousand customers press 'hold' on the last ten seats at the same moment, and exactly ten must win." This repository races 1,000 virtual threads against each store, and a second race (a confirm and the expiry sweep on one seat) 100 times. It also needs a background sweeper that stops cleanly when the program is told to stop.

## Where it appears

| Path | What it does with concurrency |
| --- | --- |
| `application/simulation/HoldRace.java` | 1,000 virtual threads press "hold" at once. |
| `application/simulation/SweepConfirmRace.java` | A confirm and a sweep meet on one seat. |
| `application/simulation/RendezvousSeatStore.java` | Makes two threads read before either writes. |
| `infrastructure/memory/InMemorySeatStore.java` | `ConcurrentHashMap.replace` as an atomic compare-and-set. |
| `infrastructure/memory/PlainWriteSeatStore.java` | The broken version, with a plain write. |
| `infrastructure/scheduling/ScheduledSweeper.java` | A single scheduled thread that sweeps expired holds. |
| `interfaces/cli/Main.java`, `Cli.java` | A shutdown hook and a latch that let `serve` stop cleanly. |
| `src/test/.../SeatStoreContract.java`, `HoldRaceTest.java` and others | Tests with threads. |

## How it works underneath

**The race.** Two customers read the seat and both see "free". Both decide "I can hold it". Both write. Without protection, both writes succeed and the seat sells twice. The decision was right when it was made; it was wrong by the time it was written.

**Atomic.** An **atomic** operation happens as one indivisible step: no other thread can see it half done. **Compare-and-set** (CAS) is the atomic operation "set this to the new value only if it still has the value I expect; tell me whether it worked". It is the building block of safe concurrent code. `ConcurrentHashMap.replace(key, oldValue, newValue)` is exactly that.

**Blocking and waiting.** A thread that calls `latch.await()` stops until the latch is released. A `Future` represents the result of work running on another thread; `future.get()` waits for it.

**Executors.** An `ExecutorService` runs tasks on threads. `Executors.newVirtualThreadPerTaskExecutor()` starts a new virtual thread for each task. It is `AutoCloseable`: in a `try (...)` block, leaving the block waits for the tasks to finish.

**Signals and shutdown hooks.** The operating system stops a program politely by sending it a **signal**: `SIGINT` when you press Ctrl-C, `SIGTERM` when `kill` or `docker stop` is used. The JVM reacts by starting its **shutdown hooks** (threads registered with `Runtime.addShutdownHook`) and then exiting. By default the JVM does not wait for your `main` thread to finish its work.

## Walkthrough of this repository's use

### The atomic store

```java
 1  package io.plainround.seatholds.infrastructure.memory;
 2  
 3  import io.plainround.seatholds.domain.SeatId;
 4  import io.plainround.seatholds.domain.SeatState;
 5  import io.plainround.seatholds.domain.SeatState.Free;
 6  import io.plainround.seatholds.domain.SeatStore;
 7  import io.plainround.seatholds.domain.UnknownSeatException;
 8  import java.util.Comparator;
 9  import java.util.List;
10  import java.util.Map;
11  import java.util.concurrent.ConcurrentHashMap;
12  
13  /**
14   * Keeps seats in a concurrent map. {@link Map#replace(Object, Object, Object)} is an atomic
15   * compare-and-set: it applies only if the current value equals the expected one, which is exactly
16   * what the {@link SeatStore} contract asks for.
17   */
18  public final class InMemorySeatStore implements SeatStore {
19  
20    private final Map<SeatId, SeatState> seats = new ConcurrentHashMap<>();
21  
22    @Override
23    public SeatState get(SeatId seat) {
24      SeatState state = seats.get(seat);
25      if (state == null) {
26        throw new UnknownSeatException(seat);
27      }
28      return state;
29    }
30  
31    @Override
32    public boolean update(SeatId seat, SeatState expected, SeatState next) {
33      if (!seats.containsKey(seat)) {
34        throw new UnknownSeatException(seat);
35      }
36      return seats.replace(seat, expected, next);
37    }
38  
39    @Override
40    public boolean add(SeatId seat) {
41      return seats.putIfAbsent(seat, new Free()) == null;
42    }
43  
44    @Override
45    public List<SeatId> seats() {
46      return seats.keySet().stream().sorted(Comparator.comparing(SeatId::value)).toList();
47    }
48  }
```

| Lines | What they do |
| --- | --- |
| 3-11 | Imports the port, the states, the error, and `Comparator`, `List`, `Map` and `ConcurrentHashMap` from the standard library. |
| 18 | `public final class InMemorySeatStore implements SeatStore`: this class **promises** all four port methods. |
| 20 | `Map<SeatId, SeatState> seats = new ConcurrentHashMap<>()`: a map from seat id to state that **many threads can use safely at once**. `private final`: only this class touches it. |
| 22-29 | `get`: `seats.get(seat)` returns the state or `null` for an unknown seat; a `null` becomes `UnknownSeatException`. |
| 31-37 | `update`: first checks the seat exists (`containsKey`) and throws if not. Line 36 is the whole point: `seats.replace(seat, expected, next)` is an **atomic compare-and-set**: it replaces the value only if the current value `equals` `expected` (records compare by content), and returns `true` or `false`, as one indivisible step. |
| 39-42 | `add`: `putIfAbsent(seat, new Free())` stores a free seat only if none exists and returns `null` when it inserted, so `== null` means "added". |
| 44-47 | `seats()`: the ids as a stream, `.sorted(Comparator.comparing(SeatId::value))` orders them by their text, `.toList()` makes an unchangeable list. |

### The counter-example

```java
 1  package io.plainround.seatholds.infrastructure.memory;
 2  
 3  import io.plainround.seatholds.domain.SeatId;
 4  import io.plainround.seatholds.domain.SeatState;
 5  import io.plainround.seatholds.domain.SeatState.Free;
 6  import io.plainround.seatholds.domain.SeatStore;
 7  import io.plainround.seatholds.domain.UnknownSeatException;
 8  import java.util.Comparator;
 9  import java.util.List;
10  import java.util.Map;
11  import java.util.concurrent.ConcurrentHashMap;
12  
13  /**
14   * The mistake the lesson warns about, kept as a counter-example: {@code update} is a plain write
15   * that ignores {@code expected} and always reports success. Two callers who both read a free seat
16   * both think they won it, so the seat sells twice. It exists only so the scenario and the tests can
17   * show that failure; no composition root wires it in.
18   */
19  public final class PlainWriteSeatStore implements SeatStore {
20  
21    private final Map<SeatId, SeatState> seats = new ConcurrentHashMap<>();
22  
23    @Override
24    public SeatState get(SeatId seat) {
25      SeatState state = seats.get(seat);
26      if (state == null) {
27        throw new UnknownSeatException(seat);
28      }
29      return state;
30    }
31  
32    @Override
33    public boolean update(SeatId seat, SeatState expected, SeatState next) {
34      if (!seats.containsKey(seat)) {
35        throw new UnknownSeatException(seat);
36      }
37      seats.put(seat, next);
38      return true;
39    }
40  
41    @Override
42    public boolean add(SeatId seat) {
43      return seats.putIfAbsent(seat, new Free()) == null;
44    }
45  
46    @Override
47    public List<SeatId> seats() {
48      return seats.keySet().stream().sorted(Comparator.comparing(SeatId::value)).toList();
49    }
50  }
```

Identical to the store above except lines 33-39: `update` checks that the seat exists, then `seats.put(seat, next)` **overwrites whatever is there** and `return true` claims success regardless of `expected`. Two callers who both read "free" both think they won. It exists to show the failure (`make run` prints it, `PlainWriteSeatStoreTest`); nothing in production wires it in.

### The thousand-thread race

```java
 1  package io.plainround.seatholds.application.simulation;
 2  
 3  import io.plainround.seatholds.application.HoldSeats;
 4  import io.plainround.seatholds.domain.CustomerId;
 5  import io.plainround.seatholds.domain.Outcome.Success;
 6  import io.plainround.seatholds.domain.SeatId;
 7  import java.util.ArrayList;
 8  import java.util.List;
 9  import java.util.concurrent.CountDownLatch;
10  import java.util.concurrent.ExecutionException;
11  import java.util.concurrent.ExecutorService;
12  import java.util.concurrent.Executors;
13  import java.util.concurrent.Future;
14  import java.util.concurrent.atomic.AtomicInteger;
15  
16  /**
17   * The lesson's race: many customers, each on a virtual thread, press "hold" on a few seats at the
18   * same moment. Every customer tries the seats one after another, starting at a different seat, and
19   * stops at the first hold that succeeds, so threads collide on every seat.
20   */
21  public final class HoldRace {
22  
23    private final HoldSeats holds;
24  
25    /**
26     * Creates a race over a hold use case.
27     *
28     * @param holds the inbound port the customers call
29     */
30    public HoldRace(HoldSeats holds) {
31      this.holds = holds;
32    }
33  
34    /**
35     * Runs one race.
36     *
37     * @param seats the seats up for grabs
38     * @param customers how many customers press hold
39     * @return how many won a seat and how many were refused every seat
40     */
41    public RaceResult run(List<SeatId> seats, int customers) {
42      AtomicInteger wins = new AtomicInteger();
43      AtomicInteger losses = new AtomicInteger();
44      CountDownLatch start = new CountDownLatch(1);
45      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
46        List<Future<?>> running = new ArrayList<>();
47        for (int index = 0; index < customers; index++) {
48          CustomerId customer = new CustomerId("customer-" + index);
49          int firstSeat = index % seats.size();
50          running.add(
51              pool.submit(
52                  () -> {
53                    start.await();
54                    (tryAllSeats(seats, firstSeat, customer) ? wins : losses).incrementAndGet();
55                    return null;
56                  }));
57        }
58        start.countDown();
59        for (Future<?> customer : running) {
60          customer.get();
61        }
62      } catch (InterruptedException interrupted) {
63        Thread.currentThread().interrupt();
64        throw new IllegalStateException("race interrupted", interrupted);
65      } catch (ExecutionException failed) {
66        throw new IllegalStateException("a customer's attempt failed", failed.getCause());
67      }
68      return new RaceResult(wins.get(), losses.get());
69    }
70  
71    private boolean tryAllSeats(List<SeatId> seats, int firstSeat, CustomerId customer) {
72      for (int offset = 0; offset < seats.size(); offset++) {
73        SeatId seat = seats.get((firstSeat + offset) % seats.size());
74        if (holds.hold(seat, customer) instanceof Success) {
75          return true;
76        }
77      }
78      return false;
79    }
80  }
```

| Lines | What they do |
| --- | --- |
| 3-14 | Imports. `AtomicInteger` (a counter safe to change from many threads), `CountDownLatch` (a gate), `ExecutorService` and `Executors` (run tasks on threads), `Future` (the result of a task still running). |
| 21-23 | The class holds the inbound port it calls, `HoldSeats`: it races any implementation. |
| 41 | `run(List<SeatId> seats, int customers)` returns how many won and lost. |
| 42-43 | Two atomic counters. |
| 44 | `new CountDownLatch(1)`: a gate that starts closed; threads that call `await()` wait until `countDown()` opens it. |
| 45 | `try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())`: an executor that starts a **new virtual thread for every task**. In a try-with-resources it is closed on leaving the block, which waits for the tasks to end. |
| 46-57 | For each customer number (47) build a `CustomerId` (48) and choose a **different first seat** (49: `index % seats.size()`, the remainder after division). `pool.submit(() -> {...})` starts a virtual thread running the lambda and returns a `Future`; the lambda waits at the gate (53), then calls `tryAllSeats` and increments the winners counter or the losers counter (54: `(condition ? wins : losses).incrementAndGet()` picks the counter first, then increments it). `return null;` is needed because the lambda is a task that returns a value. |
| 58 | `start.countDown()` opens the gate: all 1,000 threads, already waiting, **start at the same moment**. |
| 59-61 | `customer.get()` waits for each task to finish. |
| 62-64 | If the waiting thread is interrupted: restore the interrupt flag (`Thread.currentThread().interrupt()`) so callers can see it, and report with an exception. |
| 65-67 | If a task failed, `ExecutionException` wraps the task's real exception (`getCause()`); it is reported as a clear `IllegalStateException`. |
| 68 | Returns the totals as a `RaceResult`. |
| 71-79 | `tryAllSeats`: starting at the customer's first seat, tries each seat in turn (`(firstSeat + offset) % seats.size()` wraps around the list). `holds.hold(seat, customer) instanceof Success` is true if the hold worked, and the customer stops with `true`. If all ten refuse, `false`. Because every customer tries every seat, threads **collide on every seat**. |

```java
 1  package io.plainround.seatholds.application.simulation;
 2  
 3  /**
 4   * The count after a race for seats.
 5   *
 6   * @param wins customers who ended up holding a seat
 7   * @param losses customers who were refused every seat
 8   */
 9  public record RaceResult(int wins, int losses) {
10  
11    @Override
12    public String toString() {
13      return "wins=" + wins + " losses=" + losses;
14    }
15  }
```

A two-number record. `toString` (lines 11-14) prints `wins=10 losses=990`, the format of the lesson's expected output.

### The confirm-versus-sweep race

```java
  1  package io.plainround.seatholds.application.simulation;
  2  
  3  import io.plainround.seatholds.application.SeatHoldService;
  4  import io.plainround.seatholds.domain.Clock;
  5  import io.plainround.seatholds.domain.CustomerId;
  6  import io.plainround.seatholds.domain.Outcome.Success;
  7  import io.plainround.seatholds.domain.SeatId;
  8  import io.plainround.seatholds.domain.SeatState;
  9  import io.plainround.seatholds.domain.SeatStore;
 10  import java.time.Duration;
 11  import java.time.Instant;
 12  import java.util.concurrent.CountDownLatch;
 13  import java.util.concurrent.ExecutionException;
 14  import java.util.concurrent.ExecutorService;
 15  import java.util.concurrent.Executors;
 16  import java.util.concurrent.Future;
 17  import java.util.concurrent.TimeUnit;
 18  import java.util.concurrent.TimeoutException;
 19  
 20  /**
 21   * The lesson's subtle race: a confirm and the expiry sweep meet on one seat at the same moment.
 22   *
 23   * <p>The confirm's clock says the hold is still valid and the sweeper's clock says it has expired,
 24   * so each decides, correctly by its own time, to move the seat somewhere different. Both reads
 25   * happen before either write (a rendezvous inside the store makes that certain, not likely), so the
 26   * two compare-and-set calls compete on the identical state and exactly one can win.
 27   */
 28  public final class SweepConfirmRace {
 29  
 30    private static final Duration HOLD = Duration.ofMinutes(10);
 31    private static final Instant HELD_AT = Instant.parse("2026-01-01T00:00:00Z");
 32    private static final CustomerId ADA = new CustomerId("ada");
 33  
 34    /**
 35     * What one repetition ended as.
 36     *
 37     * @param finalState the seat's state afterwards: sold to ada or free, never anything else
 38     * @param confirmed whether the confirm reported success
 39     * @param released how many holds the sweeper reported releasing
 40     */
 41    public record Repetition(SeatState finalState, boolean confirmed, int released) {
 42  
 43      /**
 44       * Whether the repetition ended in exactly one of the two legal ways, with the two callers'
 45       * reports agreeing with the seat.
 46       *
 47       * @return true if the seat is sold and the confirm won, or free and the sweep won
 48       */
 49      public boolean isConsistent() {
 50        return (finalState instanceof SeatState.Sold && confirmed && released == 0)
 51            || (finalState instanceof SeatState.Free && !confirmed && released == 1);
 52      }
 53    }
 54  
 55    private final SeatStore store;
 56  
 57    /**
 58     * Creates the race over a store.
 59     *
 60     * @param store the store under test
 61     */
 62    public SweepConfirmRace(SeatStore store) {
 63      this.store = store;
 64    }
 65  
 66    /**
 67     * Runs one repetition on a fresh seat.
 68     *
 69     * @param seat a seat id not used before
 70     * @return how it ended
 71     */
 72    public Repetition run(SeatId seat) {
 73      store.add(seat);
 74      var rendezvous = new RendezvousSeatStore(store, 2);
 75      Clock beforeExpiry = () -> HELD_AT.plus(HOLD).minusSeconds(1);
 76      Clock afterExpiry = () -> HELD_AT.plus(HOLD).plusSeconds(1);
 77      new SeatHoldService(store, () -> HELD_AT, HOLD).hold(seat, ADA);
 78      var confirmer = new SeatHoldService(rendezvous, beforeExpiry, HOLD);
 79      var sweeper = new SeatHoldService(rendezvous, afterExpiry, HOLD);
 80      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
 81        CountDownLatch start = new CountDownLatch(1);
 82        Future<Boolean> confirm =
 83            pool.submit(
 84                () -> {
 85                  start.await();
 86                  return confirmer.confirm(seat, ADA) instanceof Success;
 87                });
 88        Future<Boolean> sweep =
 89            pool.submit(
 90                () -> {
 91                  start.await();
 92                  return sweeper.release(seat) instanceof Success;
 93                });
 94        start.countDown();
 95        boolean confirmed = confirm.get(30, TimeUnit.SECONDS);
 96        boolean released = sweep.get(30, TimeUnit.SECONDS);
 97        return new Repetition(store.get(seat), confirmed, released ? 1 : 0);
 98      } catch (InterruptedException interrupted) {
 99        Thread.currentThread().interrupt();
100        throw new IllegalStateException("race interrupted", interrupted);
101      } catch (ExecutionException | TimeoutException failed) {
102        throw new IllegalStateException("the race did not finish", failed);
103      }
104    }
105  }
```

| Lines | What they do |
| --- | --- |
| 3-18 | Imports (including `TimeUnit` and `TimeoutException` for waiting with a limit). |
| 28 | `public final class SweepConfirmRace`. |
| 30-32 | Constants: the hold length, a fixed starting moment `HELD_AT` (`Instant.parse("2026-01-01T00:00:00Z")`) and the customer ada. |
| 41-53 | The nested record `Repetition(finalState, confirmed, released)` is the report of one repetition. `isConsistent()` (49-52) is true in exactly two cases: the seat is `Sold`, the confirm reported success and nothing was released; **or** the seat is `Free`, the confirm was refused and the sweep released one. `&&` is "and", `||` is "or", and `instanceof` tests the type of the state. Anything else is a bug. |
| 55-64 | The field and constructor: the store under test. |
| 72 | `run(SeatId seat)` runs one repetition. |
| 73 | Adds the seat (free). |
| 74 | Wraps the store in a `RendezvousSeatStore` for **two** participants, so each thread's first read waits for the other's. |
| 75-76 | Two clocks, as lambdas: `beforeExpiry` says one second before the hold expires; `afterExpiry` says one second after. |
| 77 | Ada holds the seat at `HELD_AT`, through a service whose clock is fixed there. |
| 78-79 | Two services over the **same** store: the **confirmer**, whose clock says the hold is still valid, and the **sweeper**, whose clock says it has expired. Each will decide a different, correct transition from the same state. |
| 80-94 | Starts the two actions on virtual threads behind a gate (`start`) so they run together: the confirmer calls `confirm(seat, ADA)`, the sweeper calls `release(seat)`; each task returns whether its outcome was a `Success`. `start.countDown()` opens the gate. |
| 95-97 | Waits for each result with a 30-second limit, **then** reads the final state (reading first would be reading too early) and returns the `Repetition`; `released ? 1 : 0` turns the boolean into a count. |
| 98-103 | Interruption restores the flag and reports; `ExecutionException | TimeoutException` (a failed task, or a task that took more than 30 seconds) reports "the race did not finish". |

### Making the collision certain

```java
 1  package io.plainround.seatholds.application.simulation;
 2  
 3  import io.plainround.seatholds.domain.SeatId;
 4  import io.plainround.seatholds.domain.SeatState;
 5  import io.plainround.seatholds.domain.SeatStore;
 6  import java.time.Duration;
 7  import java.util.List;
 8  import java.util.concurrent.CountDownLatch;
 9  import java.util.concurrent.TimeUnit;
10  
11  /**
12   * A store decorator for the sweep-versus-confirm race: the first read by each participating thread
13   * waits until every participant has read. That makes "both decided on the same state before either
14   * wrote" certain instead of merely likely, so the compare-and-set is exercised on every repetition.
15   */
16  final class RendezvousSeatStore implements SeatStore {
17  
18    private final SeatStore delegate;
19    private final CountDownLatch everyoneHasRead;
20    private final Duration patience;
21    private final ThreadLocal<Boolean> arrived = ThreadLocal.withInitial(() -> Boolean.FALSE);
22  
23    RendezvousSeatStore(SeatStore delegate, int participants) {
24      this(delegate, participants, Duration.ofSeconds(30));
25    }
26  
27    RendezvousSeatStore(SeatStore delegate, int participants, Duration patience) {
28      this.delegate = delegate;
29      this.everyoneHasRead = new CountDownLatch(participants);
30      this.patience = patience;
31    }
32  
33    @Override
34    public SeatState get(SeatId seat) {
35      SeatState state = delegate.get(seat);
36      if (!arrived.get()) {
37        arrived.set(Boolean.TRUE);
38        everyoneHasRead.countDown();
39        awaitTheOthers();
40      }
41      return state;
42    }
43  
44    private void awaitTheOthers() {
45      try {
46        if (!everyoneHasRead.await(patience.toMillis(), TimeUnit.MILLISECONDS)) {
47          throw new IllegalStateException("the other participant never read the seat");
48        }
49      } catch (InterruptedException interrupted) {
50        Thread.currentThread().interrupt();
51        throw new IllegalStateException(
52            "interrupted while waiting for the other reader", interrupted);
53      }
54    }
55  
56    @Override
57    public boolean update(SeatId seat, SeatState expected, SeatState next) {
58      return delegate.update(seat, expected, next);
59    }
60  
61    @Override
62    public boolean add(SeatId seat) {
63      return delegate.add(seat);
64    }
65  
66    @Override
67    public List<SeatId> seats() {
68      return delegate.seats();
69    }
70  }
```

| Lines | What they do |
| --- | --- |
| 16 | A package-private class implementing `SeatStore`: it **wraps** another store (the **decorator** pattern: same interface, extra behavior). |
| 18-21 | The wrapped store, a latch counting the participants that still have to read, how long to wait (`patience`), and `ThreadLocal<Boolean> arrived`: a variable with a **separate value for each thread** (`withInitial(() -> Boolean.FALSE)` sets every thread's initial value to false). |
| 23-31 | Two constructors: the first uses 30 seconds of patience; the second takes the patience (so a test can use a short one). |
| 33-42 | `get`: reads the real state first (35). If **this thread has not arrived yet** (36), mark it arrived (37), count the latch down (38), and wait for the others (39). Later reads by the same thread, after a lost race, skip the wait. Returns the state it read (41), which is the state **before** the other thread could write. |
| 44-54 | `awaitTheOthers`: `everyoneHasRead.await(patience, MILLISECONDS)` waits until every participant has read, and returns `false` if time ran out, which throws "the other participant never read the seat" (so a bug cannot hang a test). `InterruptedException` restores the flag and reports. |
| 56-69 | `update`, `add` and `seats` simply forward to the real store. |

### The scheduled sweeper

```java
 1  package io.plainround.seatholds.infrastructure.scheduling;
 2  
 3  import io.plainround.seatholds.application.ExpireHolds;
 4  import java.lang.System.Logger;
 5  import java.lang.System.Logger.Level;
 6  import java.time.Duration;
 7  import java.util.concurrent.Executors;
 8  import java.util.concurrent.ScheduledExecutorService;
 9  import java.util.concurrent.TimeUnit;
10  
11  /**
12   * Runs the expiry sweep on a fixed delay and stops cleanly: {@link #close} lets a sweep in progress
13   * finish, then ends the thread. A sweep that throws is logged and the next one still runs.
14   */
15  public final class ScheduledSweeper implements AutoCloseable {
16  
17    private static final Logger LOG = System.getLogger(ScheduledSweeper.class.getName());
18    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);
19  
20    private final ScheduledExecutorService executor =
21        Executors.newSingleThreadScheduledExecutor(
22            task -> {
23              Thread thread = new Thread(task, "hold-sweeper");
24              thread.setDaemon(true);
25              return thread;
26            });
27  
28    /**
29     * Starts sweeping.
30     *
31     * @param holds the use case to run
32     * @param every the delay between the end of one sweep and the start of the next
33     */
34    public ScheduledSweeper(ExpireHolds holds, Duration every) {
35      executor.scheduleWithFixedDelay(
36          () -> sweepOnce(holds), every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
37    }
38  
39    private static void sweepOnce(ExpireHolds holds) {
40      try {
41        int released = holds.sweep();
42        LOG.log(Level.DEBUG, "event=sweep released={0}", released);
43      } catch (RuntimeException failure) {
44        LOG.log(Level.ERROR, "event=sweep_failed error={0}", failure.toString());
45      }
46    }
47  
48    @Override
49    public void close() {
50      executor.shutdown();
51      try {
52        if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
53          executor.shutdownNow();
54        }
55      } catch (InterruptedException interrupted) {
56        executor.shutdownNow();
57        Thread.currentThread().interrupt();
58      }
59    }
60  }
```

| Lines | What they do |
| --- | --- |
| 15 | `public final class ScheduledSweeper implements AutoCloseable`: can be closed automatically. |
| 17-18 | A logger and the longest wait when stopping: ten seconds. |
| 20-26 | `Executors.newSingleThreadScheduledExecutor(task -> {...})` creates **one** background thread that can run tasks on a timer. The lambda is a **thread factory**: it makes the thread, names it `hold-sweeper` and calls `setDaemon(true)`, so it is a **daemon** thread that does not keep the program alive by itself. |
| 34-37 | The constructor schedules `() -> sweepOnce(holds)` with `scheduleWithFixedDelay(task, firstDelay, delay, unit)`: the first run after `every`, and each next run `every` after the previous one **finishes**, so two sweeps never overlap. `every.toMillis()` converts the duration to milliseconds. |
| 39-46 | `sweepOnce`: runs `holds.sweep()` and logs at `DEBUG`. It catches `RuntimeException` and logs at `ERROR`: without the catch an exception would silently cancel every future run. |
| 48-59 | `close`: `executor.shutdown()` stops accepting new runs but lets one in progress finish; `awaitTermination` waits up to ten seconds; if it does not end, `shutdownNow()` interrupts it. An interruption while waiting does the same and restores the interrupt flag. |

### The graceful stop

```java
 1  package io.plainround.seatholds.interfaces.cli;
 2  
 3  import java.time.Duration;
 4  import java.util.concurrent.CountDownLatch;
 5  
 6  /** Entry point of the {@code seatholds} command. */
 7  public final class Main {
 8  
 9    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(15);
10  
11    private Main() {}
12  
13    /**
14     * Runs the command and exits with its code. SIGINT and SIGTERM make {@code serve} stop: the
15     * signal's shutdown hook releases it and then waits for the main thread, so the sweep in progress
16     * finishes, the pool closes and the exit code is the command's own, not 143.
17     *
18     * @param args the command line, see {@link Cli}
19     */
20    public static void main(String[] args) {
21      CountDownLatch shutdown = new CountDownLatch(1);
22      Thread main = Thread.currentThread();
23      Runtime.getRuntime()
24          .addShutdownHook(
25              new Thread(
26                  () -> {
27                    shutdown.countDown();
28                    try {
29                      main.join(SHUTDOWN_WAIT.toMillis());
30                    } catch (InterruptedException interrupted) {
31                      Thread.currentThread().interrupt();
32                    }
33                  },
34                  "shutdown-signal"));
35      int exitCode = new Cli(System.getenv(), System.out, System.err, shutdown).run(args);
36      System.out.flush();
37      Runtime.getRuntime().halt(exitCode);
38    }
39  }
```

| Lines | What they do |
| --- | --- |
| 3-4 | Imports `Duration` and `CountDownLatch`. |
| 9 | `SHUTDOWN_WAIT`: the hook waits at most 15 seconds for the main thread. |
| 11 | A private constructor: `Main` is never created. |
| 20 | `public static void main(String[] args)`: the method the JVM calls. |
| 21 | `shutdown`: a latch, the signal flag. It starts closed (count 1). |
| 22 | `Thread main = Thread.currentThread();` remembers the main thread. |
| 23-34 | `Runtime.getRuntime().addShutdownHook(new Thread(...))` registers a thread that the JVM starts when it receives a stop signal (Ctrl-C is SIGINT, `kill` and `docker stop` send SIGTERM). Inside: `shutdown.countDown()` (27) tells the command to stop; `main.join(...)` (29) **waits for the main thread to finish**, up to 15 seconds. Without the `join` the JVM would exit as soon as the hook ended and cut off the sweep in progress (the first version did exactly that and exited with code 143, which is 128 plus signal number 15). `"shutdown-signal"` names the thread. |
| 35 | Builds the `Cli` with the real environment, output and error streams and the latch, and runs the command; `exitCode` is its result. |
| 36 | Flushes standard output so buffered text is written. |
| 37 | `Runtime.getRuntime().halt(exitCode)` ends the process at once with the command's own exit code (0 after a graceful stop). `halt` skips remaining hooks, which is fine here: the hook's only job was to wait. |

In `Cli`, `serve` creates the `ScheduledSweeper`, prints "serving", then `shutdown.await()` waits for the latch; `finally` closes the sweeper (line numbers and table in `java.md`).

```java
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
```

## Try it yourself

1. `make race RUNS=3` runs three races of 1,000 virtual threads; each prints `race result: wins=10 losses=990`.
2. `make run | grep -c "wins=10 losses=990"` prints `5`.
3. Start the sweeper and stop it politely: `make build`, then `java -cp "target/classes:target/lib/*" io.plainround.seatholds.interfaces.cli.Main serve &`, wait a few seconds, then `kill -TERM $!` (the `&` runs it in the background and `$!` is its process id). It prints `stopping: finishing the sweep in progress` and exits with status 0.
4. Run the broken store: `make run | tail -1` prints the line where both updates return `true`.
5. `jshell` then `var map = new java.util.concurrent.ConcurrentHashMap<String,String>(); map.put("k","a"); map.replace("k","a","b")` prints `true`; run `map.replace("k","a","c")` and it prints `false`.

## Mistakes people make

- **`synchronized` or a lock around pure functions** as the fix for a race. The race is between a read and a write at the store, and a lock inside one JVM does nothing for a second server.
- **A plain write instead of compare-and-set.** It always "succeeds".
- **Testing a race with one task per seat.** With no collisions, even a broken store passes.
- **Ignoring `InterruptedException`.** Swallowing it hides a request to stop; restore the flag.
- **A scheduled task that can throw.** The scheduler cancels future runs after an uncaught exception. `sweepOnce` catches it.
- **Exiting before the work is done.** A shutdown hook that does not wait for `main` cuts work off.

## Check yourself

1. *What is a race condition?* A bug where the result depends on the timing of threads touching shared data.
2. *What does compare-and-set do?* Atomically sets a value only if it still equals the expected one, and reports whether it did.
3. *Why does `HoldRace` use a latch?* So all 1,000 virtual threads start at the same moment.
4. *Why does the shutdown hook call `main.join(...)`?* So the JVM does not exit before the main thread finishes the work in progress.
5. *What exit status does SIGTERM give by default, and why did this repository change it?* 143 (128 plus 15); a graceful stop is a success, so `Main` now ends with the command's own code, 0.

## Further reading

- Virtual threads (JEP 444, final in Java 21): <https://openjdk.org/jeps/444>
- `java.util.concurrent` (Java 21): <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html>
- `ConcurrentHashMap`: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html>
- `Runtime.addShutdownHook`: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Runtime.html#addShutdownHook(java.lang.Thread)>
