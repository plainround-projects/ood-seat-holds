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
