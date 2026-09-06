package org.example.flightsearch.app.service;

import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.db.repository.AirportRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The two lists the search form is built from - every airport we hold flights for, and the
 * airlines with flights to filter by. They are the same for everyone and change only when
 * collection runs, but were queried afresh on every visit to the page.
 *
 * <p>It used to be every destination reachable from Poland, which was the same list the "To"
 * field needed and a list the "From" field could not use at all: departing was a fixed dozen
 * Polish airports written into the page. Both sides now offer the same thing, because a search
 * from Milan to Barcelona is one the collected data can already answer.
 */
@Service
public class SearchFormOptions {
    private static final Duration TTL = Duration.ofMinutes(5);

    private final AirportRepository airportRepository;
    private final RouteRepository routeRepository;

    private volatile Cached cached;

    private record Cached(List<AirportEntity> airports, List<Airline> airlines, Instant builtAt) {}

    public SearchFormOptions(AirportRepository airportRepository, RouteRepository routeRepository) {
        this.airportRepository = airportRepository;
        this.routeRepository = routeRepository;
    }

    public List<AirportEntity> airports() {
        return current().airports();
    }

    public List<Airline> airlines() {
        return current().airlines();
    }

    private Cached current() {
        Cached current = cached;
        if (current != null && Duration.between(current.builtAt(), Instant.now()).compareTo(TTL) < 0) {
            return current;
        }
        List<AirportEntity> airports = new ArrayList<>();
        airportRepository.findAirportsWithFlights().forEach(airports::add);
        Cached rebuilt = new Cached(airports, routeRepository.findAirlinesWithFlights(), Instant.now());
        cached = rebuilt;
        return rebuilt;
    }
}
