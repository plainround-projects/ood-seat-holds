package io.plainround.seatholds.application.simulation;

/**
 * The count after a race for seats.
 *
 * @param wins customers who ended up holding a seat
 * @param losses customers who were refused every seat
 */
public record RaceResult(int wins, int losses) {

  @Override
  public String toString() {
    return "wins=" + wins + " losses=" + losses;
  }
}
