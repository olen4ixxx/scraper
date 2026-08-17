package org.example.flightsearch.app.service;

import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.CollectionRefusedException;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.Airport;
import org.example.flightsearch.db.entity.RouteEntity;
import org.example.flightsearch.db.repository.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    // A route asked about more recently than this is skipped - what makes a long run
    // interruptible: stop it anytime, run collection again and it only fetches what's
    // missing or stale instead of starting over from scratch. Kept well under the
    // 5h minimum gap between scheduled runs (07/12/17/22 CEST), not equal to it - a run
    // doesn't collect every route at exactly its start time (boot + discovery + queueing
    // delay each route's actual collection by minutes), and GitHub's own cron trigger can
    // fire a few minutes late too. Without this margin, routes collected slightly late in
    // one run would still read as "fresh" 5h later and get skipped for a full extra cycle.
    private static final Duration FRESHNESS_WINDOW = Duration.ofHours(4);

    private final List<AirlineCollector> collectors;
    private final AirportResolver airportResolver;
    private final RoutePersistenceService routePersistenceService;
    private final RouteRepository routeRepository;

    public CollectionService(List<AirlineCollector> collectors,
                             AirportResolver airportResolver,
                             RoutePersistenceService routePersistenceService,
                             RouteRepository routeRepository) {
        this.collectors = collectors;
        this.airportResolver = airportResolver;
        this.routePersistenceService = routePersistenceService;
        this.routeRepository = routeRepository;
    }

    public void collectAll() {
        collectAll(false);
    }

    public void collectAll(boolean rediscoverRoutes) {
        logger.info("Starting collection for all airlines...");

        for (AirlineCollector collector : collectors) {
            collectAirline(collector, rediscoverRoutes);
        }

        logger.info("Collection completed");
    }

    public void collectAirline(Airline airline) {
        collectAirline(airline, false);
    }

    public void collectAirline(Airline airline, boolean rediscoverRoutes) {
        logger.info("Collecting data for airline: {}", airline);

        collectors.stream()
            .filter(c -> c.airline() == airline)
            .findFirst()
            .ifPresentOrElse(
                collector -> collectAirline(collector, rediscoverRoutes),
                () -> logger.warn("No collector found for airline: {}", airline)
            );
    }

    private void collectAirline(AirlineCollector collector, boolean rediscoverRoutes) {
        long startTime = System.currentTimeMillis();
        logger.info("Starting {} collection", collector.airline());

        try {
            List<RouteDto> routes = resolveRoutes(collector, rediscoverRoutes);
            logger.info("Found {} routes for {}", routes.size(), collector.airline());

            AtomicInteger totalFlights = new AtomicInteger();
            AtomicInteger skippedRoutes = new AtomicInteger();
            AtomicInteger skippedFresh = new AtomicInteger();
            Semaphore concurrencyLimit = new Semaphore(ROUTE_CONCURRENCY);
            // Set by whichever route first finds the site has stopped answering. Routes run on
            // their own threads, so a refusal can't simply propagate out of here - it has to be
            // handed back. Once it is, the remaining routes are dropped rather than sent.
            AtomicReference<RuntimeException> refused = new AtomicReference<>();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (RouteDto routeDto : routes) {
                    executor.submit(() -> {
                        if (refused.get() != null) {
                            return;
                        }
                        try {
                            concurrencyLimit.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            processRoute(collector, routeDto, totalFlights, skippedRoutes, skippedFresh, refused);
                        } finally {
                            concurrencyLimit.release();
                        }
                    });
                }
                // Leaving this try-with-resources block waits here until every submitted
                // route has finished (ExecutorService.close() shuts down and awaits termination).
            }

            long duration = System.currentTimeMillis() - startTime;
            RuntimeException refusal = refused.get();
            if (refusal != null) {
                logger.error("{} collection abandoned after {}ms with {} flights collected: {}",
                    collector.airline(), duration, totalFlights.get(), refusal.getMessage());
                throw refusal;
            }
            logger.info("{} collection completed: routes={}, skipped={}, alreadyFresh={}, flights={}, duration={}ms",
                collector.airline(), routes.size(), skippedRoutes.get(), skippedFresh.get(), totalFlights.get(), duration);

            // A pass that visited routes and came back with nothing is not a successful pass. It
            // used to read as one, which is how WizzAir and Transavia sat still for days behind a
            // green job.
            int attempted = routes.size() - skippedRoutes.get() - skippedFresh.get();
            if (attempted > 0 && totalFlights.get() == 0) {
                logger.error("{} collected no fares at all from {} routes - the run finished, but it "
                    + "achieved nothing, so treat this as a failure and look at why",
                    collector.airline(), attempted);
            }

        } catch (Exception e) {
            logger.error("Failed to collect data for {}", collector.airline(), e);
            throw new RuntimeException("Collection failed for " + collector.airline(), e);
        }
    }

    /**
     * Route discovery costs wildly different amounts per airline: WizzAir publishes its whole
     * network in one request, while Volotea publishes no route list at all and has to be asked
     * about every airport pair - ten thousand requests, three quarters of an hour, to arrive at
     * the same answer as last time. Networks change with the season, not between the runs of a
     * six-hourly job, so a scheduled run reuses what was found before and only the occasional
     * run rediscovers.
     */
    private List<RouteDto> resolveRoutes(AirlineCollector collector, boolean rediscoverRoutes) {
        if (!rediscoverRoutes) {
            List<RouteDto> known = new ArrayList<>();
            for (RouteEntity route : routeRepository.findByAirline(collector.airline())) {
                known.add(new RouteDto(route.id(), route.airline(), route.fromAirport(), route.toAirport()));
            }
            if (!known.isEmpty()) {
                logger.info("Reusing {} known {} routes instead of rediscovering them",
                    known.size(), collector.airline());
                return known;
            }
            logger.info("No {} routes stored yet - discovering them", collector.airline());
        }

        List<RouteDto> discovered = collector.loadRoutes();
        storeRoutes(collector, discovered);
        return discovered;
    }

    /**
     * Writes the discovered network down before any fares are fetched.
     *
     * <p>Routes used to reach the database only as a side effect of collecting a route's fares,
     * which meant a discovery that finished was still thrown away if the fare pass behind it did
     * not. Transavia showed what that costs: a 29-minute scan found 364 routes, the pass was cut
     * short 30 routes in, and 30 was what the next run reused - a tenth of the network, stored
     * with nothing to say it was partial.
     */
    private void storeRoutes(AirlineCollector collector, List<RouteDto> routes) {
        List<Long> discoveredIds = new ArrayList<>();
        for (RouteDto routeDto : routes) {
            Optional<Airport> fromAirport = airportResolver.resolve(routeDto.fromAirport());
            Optional<Airport> toAirport = airportResolver.resolve(routeDto.toAirport());
            if (fromAirport.isEmpty() || toAirport.isEmpty()) {
                continue;
            }
            try {
                discoveredIds.add(routePersistenceService.ensureRoute(routeDto, fromAirport.get(), toAirport.get()).id());
            } catch (Exception e) {
                logger.warn("Could not store discovered route {} -> {} for {}: {}",
                    routeDto.fromAirport(), routeDto.toAirport(), collector.airline(), e.getMessage());
            }
        }
        logger.info("Stored {} discovered {} routes before collecting fares",
            discoveredIds.size(), collector.airline());

        // A discovery that produced nothing is a failed discovery, not an airline that stopped
        // flying - retiring its whole network on the strength of that would be the worst possible
        // reading of it.
        if (discoveredIds.isEmpty()) {
            return;
        }

        int reinstated = routeRepository.reinstate(discoveredIds);
        int retired = routeRepository.retireRoutesOtherThan(collector.airline(), discoveredIds);
        if (retired > 0 || reinstated > 0) {
            logger.info("{} routes: {} retired as no longer flown, {} brought back",
                collector.airline(), retired, reinstated);
        }
    }

    private void processRoute(AirlineCollector collector, RouteDto routeDto, AtomicInteger totalFlights,
                               AtomicInteger skippedRoutes, AtomicInteger skippedFresh,
                               AtomicReference<RuntimeException> refused) {
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

            // On when we last asked, not on whether that produced a row. Prices are only
            // recorded when they move, so a route whose fare is steady writes nothing for days -
            // judged by its data it would look permanently uncollected and be re-fetched every
            // pass, which is exactly the work this check exists to avoid.
            Instant since = Instant.now().minus(FRESHNESS_WINDOW);
            if (route.lastAttemptedAt() != null && route.lastAttemptedAt().isAfter(since)) {
                skippedFresh.incrementAndGet();
                return;
            }

            // Marked before the call, not after it, so a route that is refused or errors still
            // moves to the back of the queue instead of blocking the front of every later pass.
            routeRepository.markAttempted(route.id(), Instant.now());

            // Network call kept outside the DB transaction on purpose.
            List<FlightDto> flights = collector.loadFlights(routeDto);

            int saved = routePersistenceService.saveFlights(route.id(), flights);
            totalFlights.addAndGet(saved);
        } catch (CollectionRefusedException e) {
            // Not this route's problem and not something the next route would fare better at -
            // the site is turning us away, so hand it back and let the run end.
            refused.compareAndSet(null, e);
        } catch (Exception e) {
            logger.error("Failed to process route {} -> {} for {}",
                routeDto.fromAirport(), routeDto.toAirport(), collector.airline(), e);
            // This route's transaction rolled back independently; other routes are unaffected.
        }
    }
}
