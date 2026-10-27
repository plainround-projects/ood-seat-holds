package io.plainround.seatholds.domain;

import java.time.Instant;

/** Outbound port: the current time, injected so an expired hold can be tested without waiting. */
@FunctionalInterface
public interface Clock {

  /**
   * The current instant.
   *
   * @return now
   */
  Instant now();
}
