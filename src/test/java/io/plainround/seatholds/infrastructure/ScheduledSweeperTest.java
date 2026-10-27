package io.plainround.seatholds.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.ExpireHolds;
import io.plainround.seatholds.domain.Outcome;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.infrastructure.scheduling.ScheduledSweeper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScheduledSweeperTest {

  private static final class CountingSweeps implements ExpireHolds {
    final AtomicInteger sweeps = new AtomicInteger();
    final CountDownLatch threeSweeps = new CountDownLatch(3);
    final boolean failEverySweep;

    CountingSweeps(boolean failEverySweep) {
      this.failEverySweep = failEverySweep;
    }

    @Override
    public int sweep() {
      sweeps.incrementAndGet();
      threeSweeps.countDown();
      if (failEverySweep) {
        throw new IllegalStateException("store down");
      }
      return 0;
    }

    @Override
    public Outcome release(SeatId seat) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void itSweepsRepeatedlyAndStopsWhenClosed() throws InterruptedException {
    var holds = new CountingSweeps(false);

    try (var sweeper = new ScheduledSweeper(holds, Duration.ofMillis(10))) {
      assertThat(holds.threeSweeps.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(sweeper).isNotNull();
    }

    int atClose = holds.sweeps.get();
    Thread.sleep(100);
    assertThat(holds.sweeps.get()).as("no sweep after close").isEqualTo(atClose);
  }

  @Test
  void aSweepThatThrowsDoesNotStopTheNextOne() throws InterruptedException {
    var holds = new CountingSweeps(true);

    try (var sweeper = new ScheduledSweeper(holds, Duration.ofMillis(10))) {
      assertThat(holds.threeSweeps.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(sweeper).isNotNull();
    }
  }
}
