# 0003. Seat states are data and transitions are pure functions

- Status: accepted (2026-10-27)

## Context

A mutable `Seat` class with `hold()` and `confirm()` methods would need a store and a clock to be exercised, and concurrency correctness would be tangled with transition logic.

## Decision

`SeatState` is a sealed interface of records (`Free`, `Held`, `Sold`). `SeatTransitions.hold`, `confirm` and `release` take the current state and a `Clock` and return a sealed `Outcome`: `Success(newState)` or `Reason(message)`. Nothing throws, mutates or reads a store. Pattern-matching `switch` with guards expresses "held by this customer and not expired", "held by this customer but expired" and "held by someone else" as separate, exhaustively checked cases.

## Consequences

- Every transition is tested with a value and a fixed clock; no store, no threads.
- The boundary is decided once, in one place: the expiry instant itself belongs to the customer, so a confirm at that instant succeeds and a release is refused. A test proves that for any instant exactly one of confirm and release is allowed.
- Reasons are plain strings, as in the lesson. A production system would add a reason code so callers can branch without parsing text.
