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
