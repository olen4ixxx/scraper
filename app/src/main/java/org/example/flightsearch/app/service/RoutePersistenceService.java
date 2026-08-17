package org.example.flightsearch.app.service;

import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airport;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.db.entity.FlightEntity;
import org.example.flightsearch.db.entity.PriceSnapshotEntity;
import org.example.flightsearch.db.entity.RouteEntity;
import org.example.flightsearch.db.repository.AirportRepository;
import org.example.flightsearch.db.repository.FlightRepository;
import org.example.flightsearch.db.repository.PriceSnapshotRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Persists a single route (its airports + flights) in its own transaction, so that
 * a failure on one route (e.g. a constraint violation) rolls back only that route
 * instead of aborting the whole collection run.
 */
@Service
public class RoutePersistenceService {
    private static final Logger logger = LoggerFactory.getLogger(RoutePersistenceService.class);

    private final AirportRepository airportRepository;
    private final RouteRepository routeRepository;
    private final FlightRepository flightRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;

    public RoutePersistenceService(AirportRepository airportRepository,
                                    RouteRepository routeRepository,
                                    FlightRepository flightRepository,
                                    PriceSnapshotRepository priceSnapshotRepository) {
        this.airportRepository = airportRepository;
        this.routeRepository = routeRepository;
        this.flightRepository = flightRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int saveRoute(RouteDto routeDto, Airport fromAirport, Airport toAirport, List<FlightDto> flights) {
        RouteEntity route = ensureRoute(routeDto, fromAirport, toAirport);

        int saved = 0;
        for (FlightDto flightDto : flights) {
            saveFlight(flightDto, route.id());
            saved++;
        }
        return saved;
    }

    /** Creates the route (and its airports) if needed, without touching flights - used to get a
     *  route id up front so callers can check freshness before deciding whether to fetch flights at all. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RouteEntity ensureRoute(RouteDto routeDto, Airport fromAirport, Airport toAirport) {
        saveAirportIfNotExists(fromAirport);
        saveAirportIfNotExists(toAirport);
        return saveOrGetRoute(routeDto);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int saveFlights(Long routeId, List<FlightDto> flights) {
        int saved = 0;
        for (FlightDto flightDto : flights) {
            saveFlight(flightDto, routeId);
            saved++;
        }
        return saved;
    }

    private void saveAirportIfNotExists(Airport airport) {
        if (airportRepository.findByIata(airport.iata()).isEmpty()) {
            AirportEntity entity = new AirportEntity(
                null,
                airport.iata(),
                airport.name(),
                airport.city(),
                airport.country(),
                airport.lat(),
                airport.lon()
            );
            airportRepository.save(entity);
            logger.debug("Saved airport: {}", airport.iata());
        }
    }

    private RouteEntity saveOrGetRoute(RouteDto routeDto) {
        return routeRepository.findByAirlineAndFromAirportAndToAirport(
            routeDto.airline(), routeDto.fromAirport(), routeDto.toAirport()
        ).orElseGet(() -> {
            RouteEntity route = new RouteEntity(null, routeDto.airline(), routeDto.fromAirport(), routeDto.toAirport(), true, null);
            return routeRepository.save(route);
        });
    }

    private void saveFlight(FlightDto flightDto, Long routeId) {
        Instant departure = flightDto.departure().atZone(ZoneOffset.UTC).toInstant();
        Instant arrival = flightDto.arrival().atZone(ZoneOffset.UTC).toInstant();

        flightRepository.findByRouteIdAndFlightNumberAndDeparture(
            routeId, flightDto.flightNumber(), departure
        ).ifPresentOrElse(
            existingFlight -> {
                // Only when it has actually moved. A price history is a step function - it is
                // fully described by the points where it changes - so recording the same fare
                // again on every pass adds no information and a great deal of rows: of 2.35
                // million snapshots, 1.44 million were a repeat of the price before them, and
                // the table had grown to 366MB of a 540MB allowance. Re-collecting an unchanged
                // fare is the normal case, not the exception.
                if (unchangedSince(existingFlight.id(), flightDto)) {
                    return;
                }
                logger.debug("Flight {} has a new price, recording it", flightDto.flightNumber());
                PriceSnapshotEntity priceSnapshot = new PriceSnapshotEntity(
                    null,
                    existingFlight.id(),
                    flightDto.price(),
                    flightDto.currency(),
                    Instant.now()
                );
                priceSnapshotRepository.save(priceSnapshot);
            },
            () -> {
                FlightEntity flight = new FlightEntity(
                    null,
                    routeId,
                    flightDto.flightNumber(),
                    departure,
                    arrival,
                    Instant.now()
                );

                FlightEntity savedFlight = flightRepository.save(flight);

                PriceSnapshotEntity priceSnapshot = new PriceSnapshotEntity(
                    null,
                    savedFlight.id(),
                    flightDto.price(),
                    flightDto.currency(),
                    Instant.now()
                );

                priceSnapshotRepository.save(priceSnapshot);
            }
        );
    }

    /**
     * Whether the latest recorded price for this flight already says what we just fetched.
     * Currency is compared too - the same number in a different currency is a different price,
     * not the same one.
     */
    private boolean unchangedSince(Long flightId, FlightDto flightDto) {
        // Objects.equals, not ==: both prices are boxed Doubles, so == would compare references
        // and never once report a repeat.
        return priceSnapshotRepository.findLatestByFlightId(flightId)
            .filter(latest -> Objects.equals(latest.price(), flightDto.price())
                && Objects.equals(latest.currency(), flightDto.currency()))
            .isPresent();
    }
}
