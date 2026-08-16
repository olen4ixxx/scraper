package org.example.flightsearch.app.service;

import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.PolandAirports;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.db.repository.AirportRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The two lists the search form is built from - every destination reachable from Poland, and
 * the airlines with flights to filter by. They are the same for everyone and change only when
 * collection runs, but were queried afresh on every visit to the page.
 */
@Service
public class SearchFormOptions {
    private static final Duration TTL = Duration.ofMinutes(5);

    private final AirportRepository airportRepository;
    private final RouteRepository routeRepository;

    private volatile Cached cached;

    private record Cached(List<AirportEntity> destinations, List<Airline> airlines, Instant builtAt) {}

    public SearchFormOptions(AirportRepository airportRepository, RouteRepository routeRepository) {
        this.airportRepository = airportRepository;
        this.routeRepository = routeRepository;
    }

    public List<AirportEntity> destinations() {
        return current().destinations();
    }

    public List<Airline> airlines() {
        return current().airlines();
    }

    private Cached current() {
        Cached current = cached;
        if (current != null && Duration.between(current.builtAt(), Instant.now()).compareTo(TTL) < 0) {
            return current;
        }
        List<AirportEntity> destinations = new ArrayList<>();
        airportRepository.findDestinationsFrom(PolandAirports.ALL).forEach(destinations::add);
        Cached rebuilt = new Cached(destinations, routeRepository.findAirlinesWithFlights(), Instant.now());
        cached = rebuilt;
        return rebuilt;
    }
}
