-- 003_queries.sql: the queries used while running the lesson. Run with `make psql`, or
-- `docker compose exec -T db psql -U seatholds seatholds -f - < sql/003_queries.sql`.

-- How many seats are in each state? After a race over the last ten seats: ten HELD, none FREE.
SELECT state_type, count(*) AS seats FROM seats GROUP BY state_type ORDER BY state_type;

-- Who holds what, and until when? One row per held seat, soonest expiry first.
SELECT seat_id, holder, expires_at FROM seats WHERE state_type = 'HELD' ORDER BY expires_at, seat_id;

-- Which holds has the sweeper not freed yet? The expired ones, found through the partial index.
SELECT seat_id, holder, expires_at FROM seats WHERE state_type = 'HELD' AND expires_at < now() ORDER BY expires_at;

-- Is any seat inconsistent? Should return zero rows; the CHECK constraint makes it impossible.
SELECT seat_id FROM seats
WHERE (state_type = 'FREE' AND (holder IS NOT NULL OR expires_at IS NOT NULL))
   OR (state_type = 'HELD' AND (holder IS NULL OR expires_at IS NULL))
   OR (state_type = 'SOLD' AND (holder IS NULL OR expires_at IS NOT NULL));

-- Which seats changed most often? A seat held, expired and held again has a higher version.
SELECT seat_id, state_type, version FROM seats ORDER BY version DESC, seat_id LIMIT 10;
