package org.example.flightsearch.app.service;

import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.model.Airport;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.db.repository.AirportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fills in the time zone of airports stored before there was a column for it.
 *
 * <p>The zone is what makes a duration a duration. Airlines quote local times at both ends and the
 * rows keep them that way, so subtracting one from the other was measuring two different clocks:
 * Krakow to Luton read as an hour and a half rather than two and a half, and flights from Spain
 * into Morocco arrived before they left. Every airport already in the database predates the
 * column, so without this the fix would apply only to airports discovered from here on.
 *
 * <p>On startup rather than in a migration because the answer lives in the reference dataset
 * rather than in SQL, and doing it here means the same code fills a gap that appears later - a
 * row written by an older version, or an airport whose zone is corrected in the dataset. It runs
 * over a couple of hundred rows and updates only what is missing, so on every start after the
 * first it does nothing.
 */
@Component
public class AirportTimezoneBackfill {
    private static final Logger logger = LoggerFactory.getLogger(AirportTimezoneBackfill.class);

    private final AirportRepository airports;
    private final AirportResolver reference;

    public AirportTimezoneBackfill(AirportRepository airports, AirportResolver reference) {
        this.airports = airports;
        this.reference = reference;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fillInMissingZones() {
        int filled = 0;
        int unknown = 0;
        try {
            for (AirportEntity stored : airports.findAll()) {
                if (stored.timezone() != null && !stored.timezone().isBlank()) {
                    continue;
                }
                Optional<Airport> known = reference.resolve(stored.iata());
                if (known.isEmpty() || known.get().timezone() == null || known.get().timezone().isBlank()) {
                    unknown++;
                    continue;
                }
                airports.save(new AirportEntity(stored.id(), stored.iata(), stored.name(), stored.city(),
                    stored.country(), stored.lat(), stored.lon(), known.get().timezone()));
                filled++;
            }
        } catch (Exception e) {
            // A duration that is wrong in the old way is not a reason to refuse to start.
            logger.warn("Could not fill in airport time zones: {}", e.getMessage());
            return;
        }

        if (filled > 0) {
            logger.info("Filled in the time zone of {} airports", filled);
        }
        if (unknown > 0) {
            logger.warn("{} airports have no time zone in the reference dataset, so their flight "
                + "durations are still measured across two clocks", unknown);
        }
    }
}
