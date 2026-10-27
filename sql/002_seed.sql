-- 002_seed.sql: DML for the lesson's example: one event with ten seats, all free.
-- Safe to run again: existing seats are left as they are.

INSERT INTO seats (seat_id, state_type)
SELECT 's-' || n, 'FREE'
FROM generate_series(1, 10) AS n
ON CONFLICT (seat_id) DO NOTHING;
