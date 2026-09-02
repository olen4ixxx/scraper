package org.example.flightsearch.app.service;

import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a few airlines collected for as long as this instance is running.
 *
 * <p>It exists for the ones the scheduled cloud runs cannot do. Transavia answers GitHub's
 * runners 403 to every request - a flat refusal from the first call, while the same requests at
 * the same pace answer normally from a home connection - so it blocks the address rather than the
 * rate and no amount of care on our side changes that. Running the application from a machine it
 * will talk to is the whole fix.
 *
 * <p>Nothing here tracks progress, because progress is already in the database. Routes are
 * visited longest-untried first and each is stamped before it is asked about, so a run that stops
 * halfway - the machine sleeping, the container going down, a reboot - leaves the next one
 * starting exactly where it left off. Stopping is therefore always safe and never needs
 * announcing; there is no state in this process worth preserving.
 *
 * <p>A fixed delay rather than a schedule, so a pass that takes longer than the gap simply
 * finishes before the next begins instead of piling up behind itself.
 */
@Component
@ConditionalOnProperty(name = "collector.local.airlines")
public class LocalCollectionLoop {
    private static final Logger logger = LoggerFactory.getLogger(LocalCollectionLoop.class);

    private final CollectionService collectionService;
    private final List<Airline> airlines = new ArrayList<>();

    public LocalCollectionLoop(CollectionService collectionService,
                               @Value("${collector.local.airlines}") String airlines,
                               @Value("${collector.local.interval-minutes:180}") long intervalMinutes) {
        this.collectionService = collectionService;
        for (String name : airlines.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                this.airlines.add(Airline.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.error("collector.local.airlines names '{}', which is not an airline - ignoring it", trimmed);
            }
        }
        logger.info("Collecting {} locally, every {} minutes, for as long as this instance runs",
            this.airlines, intervalMinutes);
    }

    @Scheduled(fixedDelayString = "${collector.local.interval-minutes:180}", timeUnit = java.util.concurrent.TimeUnit.MINUTES,
               initialDelayString = "${collector.local.initial-delay-minutes:1}")
    public void collect() {
        for (Airline airline : airlines) {
            try {
                collectionService.collectAirline(airline);
            } catch (Exception e) {
                // One airline's bad afternoon is not a reason to stop collecting the others, or
                // to bring the container down - the next pass will try again.
                logger.error("Local collection of {} failed: {}", airline, e.getMessage());
            }
        }
    }
}
