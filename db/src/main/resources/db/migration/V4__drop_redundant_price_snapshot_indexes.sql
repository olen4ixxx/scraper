-- price_snapshot carried four indexes totalling 212MB against 155MB of actual rows, on a
-- database with a 540MB allowance. Two of them earn nothing:
--
-- idx_price_snapshot_flight is (flight_id), which is the leading column of
-- idx_price_snapshot_flight_latest (flight_id, collected_at DESC). Every query it can answer,
-- the composite answers too - the planner was simply preferring the narrower one. 24MB.
--
-- idx_price_snapshot_collected is (collected_at) alone, and nothing left in the application
-- asks a question shaped that way: every read of this table is per-flight and served by the
-- composite. Its recorded scans came from ad-hoc queries run by hand while diagnosing the
-- collectors, not from the running site. 51MB.
--
-- The primary key index is a third case - 51MB and genuinely zero scans, since nothing ever
-- looks a snapshot up by its id - but it is kept: dropping a primary key to save space is a
-- trade this database does not need to make.
DROP INDEX IF EXISTS idx_price_snapshot_flight;
DROP INDEX IF EXISTS idx_price_snapshot_collected;
