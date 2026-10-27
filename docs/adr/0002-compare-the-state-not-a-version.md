# 0002. The conditional UPDATE compares the state, not a version number

- Status: accepted (2026-10-27)

## Context

The lesson's hint for the JDBC adapter is `UPDATE ... WHERE seat_id = ? AND version = ?`. The port the lesson gives is `update(seatId, expected, next)`, where `expected` is a state, not a version. A version in the port would leak a storage detail into the domain and into the in-memory adapter, which has no use for one.

## Decision

The `WHERE` clause matches the expected state's columns (`state_type`, `holder`, `expires_at`, with `IS NOT DISTINCT FROM` for the nullable ones). The `version` column stays: it is incremented on every change so the history of a seat is visible (`sql/003_queries.sql` lists the most-changed seats), but the compare-and-set does not depend on it.

## Consequences

- The port stays state-based and both adapters implement the identical contract.
- The classic weakness of comparing state is ABA: a seat that goes `Held(ada, t)` to `Free` and back to `Held(ada, t)` looks unchanged to a stale writer. Here the two states are equal by value, so a write justified by the first is justified by the second; the compare-and-set answers "is the seat still in the state my decision was based on", and it is.
- Expiry instants are kept to the millisecond in `Held` so a value written to a column of any precision reads back equal.
