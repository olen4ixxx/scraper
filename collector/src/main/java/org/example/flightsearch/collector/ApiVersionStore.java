package org.example.flightsearch.collector;

import java.util.Optional;

/**
 * Where a collector remembers the API version an airline is currently serving.
 *
 * <p>WizzAir puts the version in the path and enforces it, and moves it every few weeks - 29.12.0
 * to 29.14.0 to 29.15.1 inside a fortnight - so a version compiled in is a version that will be
 * wrong by next month. Each move cost a whole pass and a hand edit; the first one cost nine days,
 * because a 404 read as "this route has no flights".
 *
 * <p>The runs that collect are ephemeral - a scheduled job starts the application, collects, and
 * stops - so anything one run learns has to be written down somewhere the next one can read it.
 * That is what this is for: the collector finds the new version, records it here, and every later
 * run starts from it without anyone editing a setting.
 *
 * <p>Kept as an interface because the collectors know nothing about databases, and should not have
 * to in order to remember one string.
 */
public interface ApiVersionStore {

    /** The version last known to work, if one has ever been recorded. */
    Optional<String> current(String airline);

    /** Records a version as the one that answers, for every run after this one. */
    void remember(String airline, String version);
}
