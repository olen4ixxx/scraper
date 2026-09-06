-- Somewhere for a collection run to leave a note for the next one.
--
-- The runs are ephemeral: the scheduled job starts the application, collects, and stops, so
-- nothing a run works out survives it. The one thing that needs to survive is WizzAir's API
-- version, which is part of the path, is enforced, and moves every few weeks - 29.12.0 to 29.14.0
-- to 29.15.1 inside a fortnight. Each move used to cost a whole pass and a hand edit, and the
-- first one cost nine days because a 404 read as "this route has no flights".
--
-- A general key and value rather than a wizz_api_version column, because the next thing worth
-- remembering between runs will not be this one.
CREATE TABLE collector_setting (
    name VARCHAR(80) PRIMARY KEY,
    value VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
