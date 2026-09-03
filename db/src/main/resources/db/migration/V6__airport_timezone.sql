-- Airlines quote local times at both ends of a flight, and both were being stored as though they
-- were the same clock. A Krakow to Luton flight therefore read as an hour and a half rather than
-- two and a half, and 190 flights from Spain into Morocco - an hour behind - arrived before they
-- departed and showed a duration of minus twenty-four hours.
--
-- The times themselves are right and are displayed as they are; what needs the zone is working
-- out how long a flight actually takes. Connection times are unaffected, since both sides of a
-- connection are read off the same airport's clock.
ALTER TABLE airport ADD COLUMN timezone VARCHAR(64);
