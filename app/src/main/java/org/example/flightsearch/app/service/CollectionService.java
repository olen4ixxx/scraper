package org.example.flightsearch.app.service;

import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.Airport;
import org.example.flightsearch.db.entity.RouteEntity;
import org.example.flightsearch.db.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates collection: fetches routes/flights from each collector and hands
 * persistence off to {@link RoutePersistenceService}. Routes are processed concurrently
 * (bounded by ROUTE_CONCURRENCY, one virtual thread each) since each route's work is
 * almost entirely spent waiting on the collector's HTTP call, not CPU - with thousands
 * of routes, doing them one at a time left the machine mostly idle between requests.
 * Each route still gets its own REQUIRES_NEW transaction, so one bad route can never
 * abort another, concurrent or not.
 */
@Service
public class CollectionService {
    private static final Logger logger = LoggerFactory.getLogger(CollectionService.class);
    private static final int ROUTE_CONCURRENCY = 8;
    // A route with any price collected more recently than this is skipped - what makes a
    // long run interruptible: stop it anytime, run collection again and it only fetches
    // what's missing or stale instead of starting over from scratch. Matches the GitHub
    // Actions schedule (07/12/17/22 CEST - 5h apart, except the 9h overnight gap) - if
    // this were longer than 5h, back-to-back runs would skip everything as "already
    // fresh" and collect nothing new.
    private static final Duration FRESHNESS_WINDOW = Duration.ofHours(5);

    private final List<AirlineCollector> collectors;
    private final AirportResolver airportResolver;
    private final RoutePersistenceService routePersistenceService;
    private final FlightRepository flightRepository;

    public CollectionService(List<AirlineCollector> collectors,
                             AirportResolver airportResolver,
                             RoutePersistenceService routePersistenceService,
                             FlightRepository flightRepository) {
        this.collectors = collectors;
        this.airportResolver = airportResolver;
        this.routePersistenceService = routePersistenceService;
        this.flightRepository = flightRepository;
    }

    public void collectAll() {
        logger.info("Starting collection for all airlines...");

        for (AirlineCollector collector : collectors) {
            collectAirline(collector);
        }

        logger.info("Collection completed");
    }

    public void collectAirline(Airline airline) {
        logger.info("Collecting data for airline: {}", airline);

        collectors.stream()
            .filter(c -> c.airline() == airline)
            .findFirst()
            .ifPresentOrElse(
                this::collectAirline,
                () -> logger.warn("No collector found for airline: {}", airline)
            );
    }

    private void collectAirline(AirlineCollector collector) {
        long startTime = System.currentTimeMillis();
        logger.info("Starting {} collection", collector.airline());

        try {
            List<RouteDto> routes = collector.loadRoutes();
            logger.info("Found {} routes for {}", routes.size(), collector.airline());

            AtomicInteger totalFlights = new AtomicInteger();
            AtomicInteger skippedRoutes = new AtomicInteger();
            AtomicInteger skippedFresh = new AtomicInteger();
            Semaphore concurrencyLimit = new Semaphore(ROUTE_CONCURRENCY);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (RouteDto routeDto : routes) {
                    executor.submit(() -> {
                        try {
                            concurrencyLimit.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            processRoute(collector, routeDto, totalFlights, skippedRoutes, skippedFresh);
                        } finally {
                            concurrencyLimit.release();
                        }
                    });
                }
                // Leaving this try-with-resources block waits here until every submitted
                // route has finished (ExecutorService.close() shuts down and awaits termination).
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("{} collection completed: routes={}, skipped={}, alreadyFresh={}, flights={}, duration={}ms",
                collector.airline(), routes.size(), skippedRoutes.get(), skippedFresh.get(), totalFlights.get(), duration);

        } catch (Exception e) {
            logger.error("Failed to collect data for {}", collector.airline(), e);
            throw new RuntimeException("Collection failed for " + collector.airline(), e);
        }
    }

    private void processRoute(AirlineCollector collector, RouteDto routeDto, AtomicInteger totalFlights,
                               AtomicInteger skippedRoutes, AtomicInteger skippedFresh) {
        try {
            Optional<Airport> fromAirport = airportResolver.resolve(routeDto.fromAirport());
            Optional<Airport> toAirport = airportResolver.resolve(routeDto.toAirport());

            if (fromAirport.isEmpty() || toAirport.isEmpty()) {
                String missing = fromAirport.isEmpty() ? routeDto.fromAirport() : routeDto.toAirport();
                logger.warn("Skipping route {} -> {} for {}: no airport metadata for {}",
                    routeDto.fromAirport(), routeDto.toAirport(), collector.airline(), missing);
                skippedRoutes.incrementAndGet();
                return;
            }

            RouteEntity route = routePersistenceService.ensureRoute(routeDto, fromAirport.get(), toAirport.get());

            Instant since = Instant.now().minus(FRESHNESS_WINDOW);
            if (flightRepository.existsFreshDataForRoute(route.id(), since)) {
                skippedFresh.incrementAndGet();
                return;
            }

            // Network call kept outside the DB transaction on purpose.
            List<FlightDto> flights = collector.loadFlights(routeDto);

            int saved = routePersistenceService.saveFlights(route.id(), flights);
            totalFlights.addAndGet(saved);
        } catch (Exception e) {
            logger.error("Failed to process route {} -> {} for {}",
                routeDto.fromAirport(), routeDto.toAirport(), collector.airline(), e);
            // This route's transaction rolled back independently; other routes are unaffected.
        }
    }
}
