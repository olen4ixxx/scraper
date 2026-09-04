-- Three of the five airlines publish a fare as a date and an amount with no time of day. Those
-- were stored as the ends of their own day - departing 23:59, arriving 00:01 - which reads as a
-- flight landing twenty-four hours before it takes off, and the results page showed exactly that:
-- "-23h -58m".
--
-- Looking wrong is the smaller half. A connection is judged by the gap between one leg landing
-- and the next leaving, so an invented arrival makes an invented gap: a flight that really lands
-- at ten in the evening was being offered as a comfortable change onto an eight o'clock morning
-- departure. Those itineraries are still worth showing - this is a tool for finding candidates to
-- check, not for selling seats - but they have to be marked as resting on a time nobody published.
ALTER TABLE flight ADD COLUMN time_known BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfilled by airline rather than by looking for the placeholder values. Ryanair has 190 real
-- flights from Spain into Morocco whose local arrival genuinely precedes their local departure,
-- and a rule written around "arrives before it departs" would mark those as unpublished when they
-- are simply crossing a time zone.
UPDATE flight f SET time_known = FALSE
FROM route r
WHERE r.id = f.route_id AND r.airline IN ('WIZZAIR', 'TRANSAVIA', 'VUELING');
