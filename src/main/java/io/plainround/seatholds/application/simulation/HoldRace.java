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
