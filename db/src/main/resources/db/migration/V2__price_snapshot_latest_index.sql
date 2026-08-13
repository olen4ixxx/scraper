-- Every fare query needs the most recent price per flight. With only an index on
-- flight_id, Postgres read and sorted all of a flight's snapshots to find it; this lets
-- it walk straight to the newest one instead. Measured on ~650k snapshots: a
-- Poland-wide 8-day search went from 306ms to 16ms.
CREATE INDEX IF NOT EXISTS idx_price_snapshot_flight_latest
    ON price_snapshot (flight_id, collected_at DESC);
