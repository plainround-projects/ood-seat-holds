# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-27

### Added

- The seat state machine as pure functions over a sealed `SeatState` (`Free`, `Held`, `Sold`): `hold`, `confirm` (refused after the expiry, with a reason) and `release`.
- The `SeatStore` port whose `update` is a compare-and-set, an in-memory adapter on `ConcurrentHashMap.replace`, and a PostgreSQL adapter on one conditional `UPDATE`, both passing one abstract contract test.
- `sql/`: the schema with CHECK constraints that mirror the sealed type, the ten-seat seed, and the lesson's queries; `docker-compose.yml` for the database.
- `SeatHoldService` (`HoldSeats`, `ConfirmHold`, `ExpireHolds` inbound ports), a retrying read-decide-write loop, and a scheduled sweeper with a clean shutdown.
- The race of 1,000 virtual threads for ten seats, the sweep-versus-confirm race, and a deliberately broken plain-write store that shows the seat selling twice.
- A command-line driver (`scenario`, `hold`, `confirm`, `status`, `sweep`, `race`, `serve`, `health`), environment-driven settings, layered packages with the dependency rule enforced by ArchUnit, format, lint, a coverage gate, a dependency audit and a CI workflow with a PostgreSQL integration job.
