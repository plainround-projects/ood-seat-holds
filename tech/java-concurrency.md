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
package io.plainround.seatholds.infrastructure.memory;

import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.UnknownSeatException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps seats in a concurrent map. {@link Map#replace(Object, Object, Object)} is an atomic
 * compare-and-set: it applies only if the current value equals the expected one, which is exactly
 * what the {@link SeatStore} contract asks for.
 */
public final class InMemorySeatStore implements SeatStore {

  private final Map<SeatId, SeatState> seats = new ConcurrentHashMap<>();

  @Override
  public SeatState get(SeatId seat) {
    SeatState state = seats.get(seat);
    if (state == null) {
      throw new UnknownSeatException(seat);
    }
    return state;
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    if (!seats.containsKey(seat)) {
      throw new UnknownSeatException(seat);
    }
    return seats.replace(seat, expected, next);
  }

  @Override
  public boolean add(SeatId seat) {
    return seats.putIfAbsent(seat, new Free()) == null;
  }

  @Override
  public List<SeatId> seats() {
    return seats.keySet().stream().sorted(Comparator.comparing(SeatId::value)).toList();
  }
}
```

- `ConcurrentHashMap` is a map that many threads can use safely at once.
- `update` first checks the seat exists (`containsKey`), then `seats.replace(seat, expected, next)`: it applies `next` **only if** the current value `equals` `expected` (records compare by content), and returns `true` or `false`, atomically. This is the same contract the PostgreSQL store meets with one conditional `UPDATE` (`jdbc.md`).
- `add` uses `putIfAbsent`, atomic too: it returns `null` when it inserted.

### The counter-example

```java
public boolean update(SeatId seat, SeatState expected, SeatState next) {
  if (!seats.containsKey(seat)) {
    throw new UnknownSeatException(seat);
  }
  seats.put(seat, next);
  return true;
```

`seats.put(seat, next)` ignores `expected` and `return true` always claims success. Two callers who both read "free" both think they won. It exists to show the failure (`make run` prints it; `PlainWriteSeatStoreTest`), and nothing in production wires it in.

### The thousand-thread race

```java
package io.plainround.seatholds.application.simulation;

import io.plainround.seatholds.application.HoldSeats;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The lesson's race: many customers, each on a virtual thread, press "hold" on a few seats at the
 * same moment. Every customer tries the seats one after another, starting at a different seat, and
 * stops at the first hold that succeeds, so threads collide on every seat.
 */
public final class HoldRace {

  private final HoldSeats holds;

  /**
   * Creates a race over a hold use case.
   *
   * @param holds the inbound port the customers call
   */
  public HoldRace(HoldSeats holds) {
    this.holds = holds;
  }

  /**
   * Runs one race.
   *
   * @param seats the seats up for grabs
   * @param customers how many customers press hold
   * @return how many won a seat and how many were refused every seat
   */
  public RaceResult run(List<SeatId> seats, int customers) {
    AtomicInteger wins = new AtomicInteger();
    AtomicInteger losses = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> running = new ArrayList<>();
      for (int index = 0; index < customers; index++) {
        CustomerId customer = new CustomerId("customer-" + index);
        int firstSeat = index % seats.size();
        running.add(
            pool.submit(
                () -> {
                  start.await();
                  (tryAllSeats(seats, firstSeat, customer) ? wins : losses).incrementAndGet();
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> customer : running) {
        customer.get();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("race interrupted", interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException("a customer's attempt failed", failed.getCause());
    }
    return new RaceResult(wins.get(), losses.get());
  }

  private boolean tryAllSeats(List<SeatId> seats, int firstSeat, CustomerId customer) {
    for (int offset = 0; offset < seats.size(); offset++) {
      SeatId seat = seats.get((firstSeat + offset) % seats.size());
      if (holds.hold(seat, customer) instanceof Success) {
        return true;
      }
    }
    return false;
  }
}
```

- `AtomicInteger` is a counter that can be incremented from many threads safely (`incrementAndGet`).
- `CountDownLatch start = new CountDownLatch(1)` is a gate: every task calls `start.await()` and waits there. After all 1,000 tasks have been submitted, `start.countDown()` opens the gate, so they all start **at the same moment**.
- `try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())` creates the executor; each `pool.submit(() -> {...})` starts one virtual thread running the lambda. The `Future<?>` objects are collected, and `customer.get()` waits for each.
- Each customer starts at a different seat (`index % seats.size()`) and tries the seats one after another, stopping at the first success (`tryAllSeats`). Because every customer tries every seat, threads collide on every seat: that is real contention, not 1,000 threads each owning a seat.
- The wins and losses are counted in the two `AtomicInteger`s; the result must be exactly 10 and 990.
- `InterruptedException` handling: restore the interrupt flag (`Thread.currentThread().interrupt()`) and report. `ExecutionException` wraps a failure inside a task.

### The confirm-versus-sweep race

```java
package io.plainround.seatholds.application.simulation;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.domain.Clock;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.Outcome.Success;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The lesson's subtle race: a confirm and the expiry sweep meet on one seat at the same moment.
 *
 * <p>The confirm's clock says the hold is still valid and the sweeper's clock says it has expired,
 * so each decides, correctly by its own time, to move the seat somewhere different. Both reads
 * happen before either write (a rendezvous inside the store makes that certain, not likely), so the
 * two compare-and-set calls compete on the identical state and exactly one can win.
 */
public final class SweepConfirmRace {

  private static final Duration HOLD = Duration.ofMinutes(10);
  private static final Instant HELD_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final CustomerId ADA = new CustomerId("ada");

  /**
   * What one repetition ended as.
   *
   * @param finalState the seat's state afterwards: sold to ada or free, never anything else
   * @param confirmed whether the confirm reported success
   * @param released how many holds the sweeper reported releasing
   */
  public record Repetition(SeatState finalState, boolean confirmed, int released) {

    /**
     * Whether the repetition ended in exactly one of the two legal ways, with the two callers'
     * reports agreeing with the seat.
     *
     * @return true if the seat is sold and the confirm won, or free and the sweep won
     */
    public boolean isConsistent() {
      return (finalState instanceof SeatState.Sold && confirmed && released == 0)
          || (finalState instanceof SeatState.Free && !confirmed && released == 1);
    }
  }

  private final SeatStore store;

  /**
   * Creates the race over a store.
   *
   * @param store the store under test
   */
  public SweepConfirmRace(SeatStore store) {
    this.store = store;
  }

  /**
   * Runs one repetition on a fresh seat.
   *
   * @param seat a seat id not used before
   * @return how it ended
   */
  public Repetition run(SeatId seat) {
    store.add(seat);
    var rendezvous = new RendezvousSeatStore(store, 2);
    Clock beforeExpiry = () -> HELD_AT.plus(HOLD).minusSeconds(1);
    Clock afterExpiry = () -> HELD_AT.plus(HOLD).plusSeconds(1);
    new SeatHoldService(store, () -> HELD_AT, HOLD).hold(seat, ADA);
    var confirmer = new SeatHoldService(rendezvous, beforeExpiry, HOLD);
    var sweeper = new SeatHoldService(rendezvous, afterExpiry, HOLD);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch start = new CountDownLatch(1);
      Future<Boolean> confirm =
          pool.submit(
              () -> {
                start.await();
                return confirmer.confirm(seat, ADA) instanceof Success;
              });
      Future<Boolean> sweep =
          pool.submit(
              () -> {
                start.await();
                return sweeper.release(seat) instanceof Success;
              });
      start.countDown();
      boolean confirmed = confirm.get(30, TimeUnit.SECONDS);
      boolean released = sweep.get(30, TimeUnit.SECONDS);
      return new Repetition(store.get(seat), confirmed, released ? 1 : 0);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("race interrupted", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("the race did not finish", failed);
    }
  }
}
```

- The **confirmer** is a service whose clock says "one second before the hold expires"; the **sweeper**'s clock says "one second after". Each, by its own clock, decides a different and correct transition from the same `Held` state: confirm wants `Sold`, release wants `Free`. Both then write through `update` with the same expected state, so only one can win.
- `RendezvousSeatStore` wraps the store so that both threads have **read** before either **writes**. That makes the collision certain on every repetition, not just likely.
- Two tasks are submitted to virtual threads; a latch starts them together; `confirm.get(30, TimeUnit.SECONDS)` waits at most 30 seconds (a **timeout**).
- `Repetition.isConsistent()` is the check: the seat is `Sold` and the confirm won and nothing was released, or it is `Free` and the sweep won and the confirm was refused. Anything else is a bug.

### Making the collision certain

```java
package io.plainround.seatholds.application.simulation;

import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A store decorator for the sweep-versus-confirm race: the first read by each participating thread
 * waits until every participant has read. That makes "both decided on the same state before either
 * wrote" certain instead of merely likely, so the compare-and-set is exercised on every repetition.
 */
final class RendezvousSeatStore implements SeatStore {

  private final SeatStore delegate;
  private final CountDownLatch everyoneHasRead;
  private final Duration patience;
  private final ThreadLocal<Boolean> arrived = ThreadLocal.withInitial(() -> Boolean.FALSE);

  RendezvousSeatStore(SeatStore delegate, int participants) {
    this(delegate, participants, Duration.ofSeconds(30));
  }

  RendezvousSeatStore(SeatStore delegate, int participants, Duration patience) {
    this.delegate = delegate;
    this.everyoneHasRead = new CountDownLatch(participants);
    this.patience = patience;
  }

  @Override
  public SeatState get(SeatId seat) {
    SeatState state = delegate.get(seat);
    if (!arrived.get()) {
      arrived.set(Boolean.TRUE);
      everyoneHasRead.countDown();
      awaitTheOthers();
    }
    return state;
  }

  private void awaitTheOthers() {
    try {
      if (!everyoneHasRead.await(patience.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the other participant never read the seat");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for the other reader", interrupted);
    }
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    return delegate.update(seat, expected, next);
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

- A `CountDownLatch(participants)` counts down once per thread's first read; both threads then wait until the count reaches zero (`everyoneHasRead.await(...)`).
- `ThreadLocal<Boolean> arrived` is a variable with a **separate value per thread**: it makes sure each thread counts down only on its **first** read (its later reads, after a lost race, must not wait again).
- All other methods simply forward to the real store (`delegate`). This is the **decorator** pattern: an object wrapping another with the same interface.
- If the other thread never arrives, the wait times out and throws, so a bug cannot hang a test forever.

### The scheduled sweeper

```java
package io.plainround.seatholds.infrastructure.scheduling;

import io.plainround.seatholds.application.ExpireHolds;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the expiry sweep on a fixed delay and stops cleanly: {@link #close} lets a sweep in progress
 * finish, then ends the thread. A sweep that throws is logged and the next one still runs.
 */
public final class ScheduledSweeper implements AutoCloseable {

  private static final Logger LOG = System.getLogger(ScheduledSweeper.class.getName());
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "hold-sweeper");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * Starts sweeping.
   *
   * @param holds the use case to run
   * @param every the delay between the end of one sweep and the start of the next
   */
  public ScheduledSweeper(ExpireHolds holds, Duration every) {
    executor.scheduleWithFixedDelay(
        () -> sweepOnce(holds), every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static void sweepOnce(ExpireHolds holds) {
    try {
      int released = holds.sweep();
      LOG.log(Level.DEBUG, "event=sweep released={0}", released);
    } catch (RuntimeException failure) {
      LOG.log(Level.ERROR, "event=sweep_failed error={0}", failure.toString());
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
```

- `Executors.newSingleThreadScheduledExecutor(...)` makes one background thread that can run tasks on a timer. The lambda passed as a **thread factory** names the thread `hold-sweeper` and marks it a **daemon** (a thread that does not keep the program alive by itself).
- `scheduleWithFixedDelay(task, every, every, MILLISECONDS)` runs the sweep, waits `every` after it **finishes**, runs it again (so two sweeps never overlap).
- `sweepOnce` catches `RuntimeException`: one failing sweep is logged and the next still runs. Without the catch, an exception would silently cancel all future runs.
- `close()` calls `shutdown()` (finish the current sweep, accept no more), then `awaitTermination` up to 10 seconds, then `shutdownNow()` if needed.

### The graceful stop

```java
package io.plainround.seatholds.interfaces.cli;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Entry point of the {@code seatholds} command. */
public final class Main {

  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(15);

  private Main() {}

  /**
   * Runs the command and exits with its code. SIGINT and SIGTERM make {@code serve} stop: the
   * signal's shutdown hook releases it and then waits for the main thread, so the sweep in progress
   * finishes, the pool closes and the exit code is the command's own, not 143.
   *
   * @param args the command line, see {@link Cli}
   */
  public static void main(String[] args) {
    CountDownLatch shutdown = new CountDownLatch(1);
    Thread main = Thread.currentThread();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  shutdown.countDown();
                  try {
                    main.join(SHUTDOWN_WAIT.toMillis());
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                },
                "shutdown-signal"));
    int exitCode = new Cli(System.getenv(), System.out, System.err, shutdown).run(args);
    System.out.flush();
    Runtime.getRuntime().halt(exitCode);
  }
}
```

- `shutdown` is a latch. `Runtime.getRuntime().addShutdownHook(new Thread(() -> {...}))` registers a thread the JVM starts when it receives SIGINT or SIGTERM.
- The hook counts the latch down (telling `serve` to stop) and then `main.join(15 s)`: it **waits for the main thread to finish**. Without the join, the JVM would exit as soon as the hook ended, cutting off the sweep in progress (the first version did exactly that: it exited with code 143, which is 128 + SIGTERM's number 15).
- After `run` returns, `Runtime.getRuntime().halt(exitCode)` ends the process at once with the command's own exit code (0 after a graceful stop).

In `Cli`, `serve` creates the `ScheduledSweeper`, prints "serving", then `shutdown.await()` waits for the latch; in `finally` it closes the sweeper.

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
