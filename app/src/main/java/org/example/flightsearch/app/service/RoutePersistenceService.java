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
            RouteEntity route = new RouteEntity(null, routeDto.airline(), routeDto.fromAirport(), routeDto.toAirport(), true);
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
                logger.debug("Flight {} already exists, adding price snapshot", flightDto.flightNumber());
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
}
