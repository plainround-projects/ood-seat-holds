# Java logging (`System.Logger`, `java.util.logging`)

## What it is

A **log** is a line of text a program writes to say what it did. Logging is how you see inside a running program without a debugger. Java has a built-in logging system called **`java.util.logging`** (often written JUL). Since Java 9 there is also a small, standard front door to it called **`System.Logger`**: you ask for a logger by name and call `log(level, message)`, and Java sends the message to whichever logging system is behind it (JUL by default). A **level** says how important a message is: `ERROR` (called SEVERE in JUL), `WARNING`, `INFO`, `DEBUG` (FINE in JUL).

## Why this repository uses it

The rules ask for structured logging with levels and no secrets. `System.Logger` is part of Java, so the domain-adjacent code needs no logging library. The output is one `key=value` line per event, which is easy to read, search and parse.

## Where it appears

| Path | What it does with logging |
| --- | --- |
| `src/main/java/io/plainround/seatholds/application/SeatHoldService.java` | `INFO` for a successful hold, confirm or release; `DEBUG` for a refusal. |
| `src/main/java/io/plainround/seatholds/infrastructure/scheduling/ScheduledSweeper.java` | Writes a `DEBUG` line per sweep and an `ERROR` line when a sweep fails. |
| `src/main/java/io/plainround/seatholds/interfaces/cli/Logging.java` | Configures where and how JUL prints: to standard error, one line, in `key=value` form, at the level from `LOG_LEVEL`. |
| `*/ArchitectureTest.java` | A rule forbids using `java.util.logging` outside `interfaces`, so only the front door is used in the business code. |

## How it works underneath

`System.getLogger("name")` returns a logger object. When code calls `LOG.log(Level.INFO, "text {0}", value)`, Java checks whether that level is enabled for the logger. If it is, it builds the message (replacing `{0}`, `{1}` with the extra arguments) and hands it to the logging backend. The backend (JUL) passes it to **handlers**. The default handler, `ConsoleHandler`, writes to **standard error** (`System.err`). A **formatter** turns the message record into a line of text.

Standard error is kept separate from **standard output** on purpose: a program's results go to standard output and its diagnostic messages go to standard error, so `make run > out.txt` captures the results and leaves the log on the screen.

## Walkthrough of this repository's use

### Creating a logger and logging an event

```java
private static final Logger LOG = System.getLogger(SeatHoldService.class.getName());
```
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
```

- `private static final Logger LOG = System.getLogger(...)` makes one logger per class, named after the class (`getName()` gives the full class name). `static final` means one shared, unchangeable reference.
- `LOG.log(Level.INFO, "event=... a={0} b={1}", a, b)` writes one line. The text before the first `{0}` is fixed; the braces are replaced by the values after the format string. The `key=value` shape (`event=book room=room-1 ...`) is called **logfmt**: each fact is a name and a value, so a person can read it and a tool can split it.
- The messages contain only business facts, never secrets.

### Configuring the output

```java
package io.plainround.seatholds.interfaces.cli;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Sends the JDK system logger's output to standard error as one {@code key=value} line each. */
final class Logging {

  private static final List<String> LEVELS = List.of("SEVERE", "WARNING", "INFO", "FINE");

  private Logging() {}

  static void requireKnownLevel(String level) {
    if (!LEVELS.contains(level)) {
      throw new IllegalArgumentException("LOG_LEVEL must be one of " + LEVELS + ", was " + level);
    }
  }

  static void configure(String level) {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format", "ts=%1$tFT%1$tT.%1$tL%1$tz level=%4$s %5$s%n");
    LogManager.getLogManager().reset();
    Logger root = Logger.getLogger("");
    root.addHandler(new java.util.logging.ConsoleHandler());
    Level parsed = Level.parse(level);
    root.setLevel(parsed);
    for (Handler handler : root.getHandlers()) {
      handler.setLevel(parsed);
    }
  }
}
```

- The class is `final` with a private constructor: it only holds static helper methods and is never created.
- `requireKnownLevel` checks `LOG_LEVEL` against the list `SEVERE, WARNING, INFO, FINE` and throws if it is wrong, which `AppConfig` turns into the exit-code-2 error.
- `configure(level)` first sets the JUL system property `java.util.logging.SimpleFormatter.format` to `ts=%1$tFT%1$tT.%1$tL%1$tz level=%4$s %5$s%n`. That is a **format string**: `%1$tFT%1$tT.%1$tL` prints the time (date, a literal `T`, time, a dot, milliseconds), `%1$tz` prints the machine's time-zone offset (`+0000` for UTC, `+0200` two hours ahead), `%4$s` prints the level, `%5$s` prints the message and `%n` is a line break. The numbers are argument positions: 1 = time, 4 = level, 5 = message.
- `LogManager.getLogManager().reset()` removes the default handlers. A new `ConsoleHandler` is added to the **root logger** (the ancestor of every logger).
- The level is set on the root logger and on each handler: a message must pass both to be printed. `Level.parse(level)` converts the text `FINE` into the `Level` object.

An example line printed by this repository:

```text
ts=2026-10-26T22:23:24.832+0000 level=INFO event=book room=room-1 person=ada range=09:00-10:00 outcome=Booked
```

(That is the room-booking format; the other repositories print their own `event=` names the same way.)

## Try it yourself

1. `make run 2>&1 >/dev/null | head -5` shows only the log lines (standard error) and hides the results (standard output).
2. `make run 2>/dev/null | head -5` shows only the results.
3. `LOG_LEVEL=SEVERE make run 2>&1 >/dev/null | head` hides the `INFO` lines: only severe messages pass.
4. `LOG_LEVEL=FINE make run 2>&1 >/dev/null | head` shows more detail where the code logs at a lower level.
5. `grep -rn "LOG.log" src/main | head` lists every place the code writes a log line.

## Mistakes people make

- **Logging secrets or personal data.** Only business facts are logged here.
- **Logging every refusal at `INFO`** in a busy path. The seat service logs refusals at `DEBUG`: a thousand-customer race would otherwise print thousands of lines and slow the race down (it did, before the fix).
- **Using `System.out.println` for diagnostics.** It mixes with the results and has no level. An ArchUnit rule here forbids printing outside `interfaces`.
- **Building expensive messages even when the level is off.** Pass arguments (`{0}`) instead of joining strings, so no work happens for a disabled level.
- **Forgetting the handler's level.** JUL needs both the logger and the handler to allow a level, which is why `Logging` sets both.

## Check yourself

1. *Which stream do these logs go to?* Standard error.
2. *What are the levels used here, from most to least serious?* `SEVERE` (ERROR), `WARNING`, `INFO`, `FINE` (DEBUG).
3. *What does `%4$s` mean in the format?* The fourth argument, the level, printed as text.
4. *Why does `LOG_LEVEL=SEVERE` hide `INFO` lines?* A message prints only if its level is at least as serious as the configured level.
5. *Why is `System.Logger` used instead of calling JUL directly?* It is the standard Java front door, so business code does not depend on one backend (an ArchUnit rule enforces it).

## Further reading

- `System.Logger` (Java 21): <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.Logger.html>
- `java.util.logging` overview and `SimpleFormatter` format: <https://docs.oracle.com/en/java/javase/21/docs/api/java.logging/java/util/logging/package-summary.html>
