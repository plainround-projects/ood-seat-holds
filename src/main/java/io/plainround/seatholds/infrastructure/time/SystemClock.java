package io.plainround.seatholds.infrastructure.time;

import io.plainround.seatholds.domain.Clock;
import java.time.Instant;

/** The real clock. Tests use a fixed one instead. */
public final class SystemClock implements Clock {

  @Override
  public Instant now() {
    return Instant.now();
  }
}
