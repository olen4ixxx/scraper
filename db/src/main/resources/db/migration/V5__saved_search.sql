-- The results page is reached by a short name rather than a query string of nineteen parameters,
-- and the search behind that name has to live somewhere. It was living in memory, which meant a
-- link stopped working as soon as the process restarted - and the process restarts on every
-- deploy and after every idle period on the hosting used here, so a link sent to someone in the
-- morning was unlikely to open in the afternoon.
--
-- The search is stored as JSON rather than nineteen columns: nothing queries these by their
-- parts, it is only ever fetched whole by name, and a column per field would have to be migrated
-- every time a search filter is added. A row that can no longer be read - written by an older
-- version whose fields have since changed - simply fails to resolve, and the results page sends
-- those to the search form.
CREATE TABLE saved_search (
    name VARCHAR(120) PRIMARY KEY,
    request TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Drives the housekeeping sweep, which drops names nobody has followed for a while.
CREATE INDEX idx_saved_search_last_used ON saved_search (last_used_at);
