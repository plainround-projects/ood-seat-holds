package io.plainround.seatholds.support;

import io.plainround.seatholds.domain.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test moves by hand. */
public final class MutableClock implements Clock {

  /** The instant the lesson's examples start from. */
  public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private final AtomicReference<Instant> now = new AtomicReference<>(START);

  @Override
  public Instant now() {
    return now.get();
  }

  public void advance(Duration by) {
    now.updateAndGet(current -> current.plus(by));
  }

  public void set(Instant instant) {
    now.set(instant);
  }
}
