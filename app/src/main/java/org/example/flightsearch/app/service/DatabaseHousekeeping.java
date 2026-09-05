package org.example.flightsearch.app.service;

import org.example.flightsearch.db.repository.FlightRepository;
import org.example.flightsearch.db.repository.PriceSnapshotRepository;
import org.example.flightsearch.db.repository.SavedSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Drops what can no longer be used, so the database reaches a plateau instead of growing forever.
 *
 * <p>Three kinds of dead weight: flights whose departure has passed, which no search can return
 * because the search window starts at today; flights left without any price, which are unreachable
 * because every search reads a price through a LATERAL join; and price history beyond the few
 * points a graph is drawn from.
 *
 * <p>One run at a time, and that is why this is its own class. The scheduled job runs five
 * airlines as five concurrent jobs, each of which used to start by sweeping the same few hundred
 * thousand rows - and three of them deadlocked against each other on the first run after this was
 * added, so the sweep failed and the tables kept growing. An advisory lock settles it: whoever
 * gets there first does the work and the others skip it, which is right anyway, since the work is
 * the same whichever airline is about to be collected.
 *
 * <p>The lock is transaction-scoped, so it is released by the commit or rollback that ends this
 * method rather than needing to be given back by hand. A session-scoped one taken through a
 * connection pool would be returned to the pool still held.
 */
@Service
public class DatabaseHousekeeping {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseHousekeeping.class);

    /**
     * An arbitrary constant, meaningful only in that every instance of this application uses the
     * same one. Postgres advisory locks are just numbers agreed on between the things that take
     * them.
     */
    private static final long LOCK_KEY = 8_314_207_001L;

    /**
     * How much of a flight's price history is worth keeping.
     *
     * <p>This is the one number that decides the size of the database: of 2.2 million price rows,
     * only one per flight is the current price a search reads, and the rest exist to draw the
     * history. It was twenty, and twenty did not fit - a flight sits in the sixty-day window long
     * enough to reach any small cap, so 275,000 flights at twenty points came to some 800MB
     * against an allowance of 540MB. At five it comes to around 250MB.
     *
     * <p>Five points is roughly four days of movement on a Ryanair fare and a fortnight on a
     * WizzAir one. What it gives up is the far end of a long-lived flight's history; the part near
     * departure, which is the part anyone decides on, is kept in full.
     */
    private static final int PRICE_POINTS_KEPT_PER_FLIGHT = 5;

    /**
     * How long a results address stays reachable after the last time anyone opened it. Long enough
     * that a link sent to someone still works when they get round to it; short enough that the
     * searches nobody returns to do not accumulate forever.
     */
    private static final Duration SAVED_SEARCH_LIFETIME = Duration.ofDays(90);

    private final FlightRepository flights;
    private final PriceSnapshotRepository prices;
    private final SavedSearchRepository savedSearches;
    private final JdbcTemplate jdbc;

    public DatabaseHousekeeping(FlightRepository flights, PriceSnapshotRepository prices,
                                SavedSearchRepository savedSearches, JdbcTemplate jdbc) {
        this.flights = flights;
        this.prices = prices;
        this.savedSearches = savedSearches;
        this.jdbc = jdbc;
    }

    /**
     * Run before collecting rather than on a timer, because there is no timer to run on: the
     * scheduled job starts the application, collects, and stops. Deleting first also means the
     * space freed is space the pass about to run can write into, which is what keeps a plateau a
     * plateau - Postgres reuses the room a delete leaves behind, so the files stop growing without
     * anyone having to compact them by hand.
     */
    @Transactional
    public void removeDeadWeight() {
        try {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY))) {
                logger.info("Another run is already clearing out the database, so this one goes "
                    + "straight to collecting");
                return;
            }

            Instant departed = Instant.now();
            int priceRows = prices.deleteForDepartedFlights(departed);
            int departedFlights = flights.deleteDepartedFlights(departed);
            int priceless = flights.deletePricelessFlights();
            if (departedFlights > 0 || priceless > 0) {
                logger.info("Cleared {} departed flights ({} price rows) and {} left without a price",
                    departedFlights, priceRows, priceless);
            }

            int trimmed = prices.trimHistoryToNewest(PRICE_POINTS_KEPT_PER_FLIGHT);
            if (trimmed > 0) {
                logger.info("Trimmed {} price points beyond the newest {} per flight",
                    trimmed, PRICE_POINTS_KEPT_PER_FLIGHT);
            }

            int forgotten = savedSearches.deleteUnusedSince(Instant.now().minus(SAVED_SEARCH_LIFETIME));
            if (forgotten > 0) {
                logger.info("Forgot {} saved searches nobody had opened in {} days",
                    forgotten, SAVED_SEARCH_LIFETIME.toDays());
            }
        } catch (Exception e) {
            // Housekeeping must never be the reason a collection run fails. The tables growing for
            // one more pass costs nothing; not collecting for a pass costs the data.
            logger.warn("Could not clear out the database: {}", e.getMessage());
        }
    }
}
