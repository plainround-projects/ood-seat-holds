-- 001_schema.sql: DDL for the seat store (PostgreSQL 14 or newer; developed on 18).
-- Run order: 001_schema.sql, 002_seed.sql. `make db` applies both; the integration tests apply this file.

CREATE TABLE IF NOT EXISTS seats (
    seat_id    text        PRIMARY KEY,
    state_type text        NOT NULL,
    holder     text,
    expires_at timestamptz,
    version    bigint      NOT NULL DEFAULT 0,

    CONSTRAINT seats_state_type_known
        CHECK (state_type IN ('FREE', 'HELD', 'SOLD')),

    -- The same states the Java sealed type allows, made unrepresentable here too:
    -- a free seat has no holder and no expiry, a held seat has both, a sold seat has a holder only.
    CONSTRAINT seats_state_shape CHECK (
           (state_type = 'FREE' AND holder IS NULL     AND expires_at IS NULL)
        OR (state_type = 'HELD' AND holder IS NOT NULL AND expires_at IS NOT NULL)
        OR (state_type = 'SOLD' AND holder IS NOT NULL AND expires_at IS NULL)
    )
);

COMMENT ON TABLE  seats            IS 'One row per seat: its state machine position (free, held, sold).';
COMMENT ON COLUMN seats.state_type IS 'FREE, HELD or SOLD; the Java SeatState variant.';
COMMENT ON COLUMN seats.holder     IS 'Customer id of the holder (HELD) or the buyer (SOLD).';
COMMENT ON COLUMN seats.expires_at IS 'Last instant a hold can be confirmed; only for HELD.';
COMMENT ON COLUMN seats.version    IS 'Bumped on every change; for auditing. The compare-and-set matches the state columns.';

-- The sweeper looks for expired holds; this partial index keeps that scan to the held seats.
CREATE INDEX IF NOT EXISTS seats_held_by_expiry ON seats (expires_at) WHERE state_type = 'HELD';
