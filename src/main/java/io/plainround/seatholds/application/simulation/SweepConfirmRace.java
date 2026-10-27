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
