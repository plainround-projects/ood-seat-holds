package io.plainround.seatholds.domain;

/** What a transition decides: the next state, or a reason there is none. Never an exception. */
public sealed interface Outcome permits Outcome.Success, Outcome.Reason {

  /**
   * The transition is allowed.
   *
   * @param newState the state the seat should move to
   */
  record Success(SeatState newState) implements Outcome {}

  /**
   * The transition is refused.
   *
   * @param message why, in words a caller can show
   */
  record Reason(String message) implements Outcome {}
}
