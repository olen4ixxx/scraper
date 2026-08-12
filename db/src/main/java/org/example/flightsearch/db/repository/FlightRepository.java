package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.FlightEntity;
import org.example.flightsearch.db.entity.FlightWithPrice;
import org.example.flightsearch.db.entity.PriceSnapshotEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FlightRepository extends CrudRepository<FlightEntity, Long> {
    
    @Query("SELECT * FROM flight WHERE route_id = :routeId AND departure >= :from AND departure <= :to ORDER BY departure")
    List<FlightEntity> findByRouteIdAndDepartureBetween(
        @Param("routeId") Long routeId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
    
    @Query("SELECT * FROM flight WHERE route_id = :routeId AND flight_number = :flightNumber AND departure = :departure")
    Optional<FlightEntity> findByRouteIdAndFlightNumberAndDeparture(
        @Param("routeId") Long routeId,
        @Param("flightNumber") String flightNumber,
        @Param("departure") Instant departure
    );
    
    // "ps.price >= 5" excludes the rare junk/sentinel prices some scrapers leave behind
    // (e.g. a promo-teaser price under 5 that never was a real bookable fare) - a real
    // budget-airline one-way fare is never genuinely that cheap.
    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        JOIN price_snapshot ps ON f.id = ps.flight_id
        WHERE r.from_airport = :fromAirport AND r.to_airport = :toAirport
        AND f.departure >= :from AND f.departure <= :to
        AND ps.price >= 5
        AND ps.collected_at = (
            SELECT MAX(collected_at) FROM price_snapshot
            WHERE flight_id = f.id
        )
        ORDER BY ps.price ASC
        """)
    List<FlightWithPrice> findDirectFlights(
        @Param("fromAirport") String fromAirport,
        @Param("toAirport") String toAirport,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        JOIN price_snapshot ps ON f.id = ps.flight_id
        WHERE r.from_airport = :airport
        AND f.departure >= :from AND f.departure <= :to
        AND ps.price >= 5
        AND ps.collected_at = (
            SELECT MAX(collected_at) FROM price_snapshot
            WHERE flight_id = f.id
        )
        ORDER BY f.departure ASC
        """)
    List<FlightWithPrice> findFlightsFrom(
        @Param("airport") String airport,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        JOIN price_snapshot ps ON f.id = ps.flight_id
        WHERE r.from_airport = :fromAirport
        AND f.departure >= :from AND f.departure <= :to
        AND ps.price >= 5
        AND ps.collected_at = (
            SELECT MAX(collected_at) FROM price_snapshot
            WHERE flight_id = f.id
        )
        ORDER BY f.departure ASC
        """)
    List<FlightWithPrice> findFlightsFromAnyDestination(
        @Param("fromAirport") String fromAirport,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    // Batched connecting-flight lookups for one-stop search: one query covering every
    // candidate connection airport at once, instead of one query per first-leg flight.
    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        JOIN price_snapshot ps ON f.id = ps.flight_id
        WHERE r.from_airport IN (:fromAirports) AND r.to_airport = :toAirport
        AND f.departure >= :from AND f.departure <= :to
        AND ps.price >= 5
        AND ps.collected_at = (
            SELECT MAX(collected_at) FROM price_snapshot
            WHERE flight_id = f.id
        )
        ORDER BY f.departure ASC
        """)
    List<FlightWithPrice> findDirectFlightsFromAirports(
        @Param("fromAirports") Iterable<String> fromAirports,
        @Param("toAirport") String toAirport,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        JOIN price_snapshot ps ON f.id = ps.flight_id
        WHERE r.from_airport IN (:fromAirports)
        AND f.departure >= :from AND f.departure <= :to
        AND ps.price >= 5
        AND ps.collected_at = (
            SELECT MAX(collected_at) FROM price_snapshot
            WHERE flight_id = f.id
        )
        ORDER BY f.departure ASC
        """)
    List<FlightWithPrice> findFlightsFromAnyDestinationFromAirports(
        @Param("fromAirports") Iterable<String> fromAirports,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    // Lets collection be interrupted and resumed later (even the next day) without redoing
    // work: a route with any price collected since the cutoff is considered "done for now"
    // and skipped, so re-running collection only fetches routes that are missing or stale.
    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM flight f
            JOIN price_snapshot ps ON ps.flight_id = f.id
            WHERE f.route_id = :routeId AND ps.collected_at >= :since
        )
        """)
    boolean existsFreshDataForRoute(@Param("routeId") Long routeId, @Param("since") Instant since);
}
