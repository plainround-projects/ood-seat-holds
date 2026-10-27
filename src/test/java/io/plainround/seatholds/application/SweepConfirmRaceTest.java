package io.plainround.seatholds.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatholds.application.simulation.SweepConfirmRace;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import org.junit.jupiter.api.Test;

class SweepConfirmRaceTest {

  @Test
  void acrossOneHundredRepetitionsASeatIsNeverBothSoldAndReleased() {
    var race = new SweepConfirmRace(new InMemorySeatStore());

    for (int rep = 1; rep <= 100; rep++) {
      var result = race.run(new SeatId("rep-" + rep));

      assertThat(result.isConsistent())
          .as(
              "rep %d ended as %s, confirmed=%s, released=%d",
              rep, result.finalState(), result.confirmed(), result.released())
          .isTrue();
    }
  }
}
