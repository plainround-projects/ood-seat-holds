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
