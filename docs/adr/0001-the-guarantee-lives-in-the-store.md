# 0001. "Sold at most once" lives in the store's compare-and-set

- Status: accepted (2026-10-27)

## Context

Two customers press "hold" on the last seat in the same millisecond. Each reads the seat as free, each decides, correctly, to hold it, and each writes. Nothing in the domain can tell them apart: a `Held` or `Sold` record is just data once it exists. `synchronized` around the transition functions would close nothing (they share no state) and would stop working the moment there are two application servers.

## Decision

`SeatStore.update(seat, expected, next)` is a compare-and-set: it applies `next` only if the seat is still in `expected`, and returns false otherwise. That one method is where the guarantee lives. The in-memory adapter uses `ConcurrentHashMap.replace(key, old, new)`; the JDBC adapter uses one `UPDATE ... WHERE seat_id = ? AND <the expected state's columns>`. The domain's transitions are pure; the service reads, decides, and writes through `update`, and on a false it reads again and decides again.

## Consequences

- Correct across processes: the database arbitrates, not a lock inside one JVM.
- Every adapter must pass the same abstract contract test, including "stale expected is rejected" and "two concurrent updates, exactly one wins". `PlainWriteSeatStore` (a plain `put` that always succeeds) is kept as the counter-example the contract catches.
- The sweeper and the confirm are two writers of the same seat and compete on the same `(seat, expected)`; neither can overwrite the other. That is the answer to the lesson's step 6 and needs no extra code.
- A transient database error during `update` is not retried blindly: the statement may have committed, and a retry would then report false for a write that happened. The caller sees `SeatStoreException` and decides.
