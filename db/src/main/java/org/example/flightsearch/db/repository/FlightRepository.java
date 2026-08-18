package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.FlightEntity;
import org.example.flightsearch.db.entity.FlightWithPrice;
import org.springframework.data.jdbc.repository.query.Modifying;
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
    
    // The LATERAL sub-select is how each flight's current price is found: walk that flight's
    // snapshots newest-first (idx_price_snapshot_flight_latest) and take one. Matching on
    // "collected_at = (SELECT MAX(...))" instead made the planner hash the entire
    // price_snapshot table on every search - a sequential scan of ~650k rows to answer a
    // question about ~2400 flights, and by far the slowest part of a search.
    //
    // Prices come back in whatever currency the airline quoted, so nothing is filtered or
    // compared by amount here - five is a fare in euros and small change in forints. Converting
    // to euros and discarding the junk ones happens in the search, on the way out of this.
    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        CROSS JOIN LATERAL (
            SELECT price, currency FROM price_snapshot
            WHERE flight_id = f.id
            ORDER BY collected_at DESC
            LIMIT 1
        ) ps
        WHERE r.from_airport IN (:fromAirports)
        AND r.active
        AND f.departure >= :from AND f.departure <= :to
        ORDER BY f.departure ASC
        """)
    List<FlightWithPrice> findFlightsFromAnyDestinationFromAirports(
        @Param("fromAirports") Iterable<String> fromAirports,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    // Both sides batched: one query for every origin/destination combination the search covers,
    // instead of one per (origin, destination) pair. Searching "all of Poland -> all of Italy"
    // is 11 x 29 pairs, which used to mean 319 separate round trips to the database - the
    // dominant cost once the database stopped being local (~40ms each against a hosted one).
    @Query("""
        SELECT f.id, f.route_id, f.flight_number, f.departure, f.arrival, f.updated_at, ps.price, ps.currency FROM flight f
        JOIN route r ON f.route_id = r.id
        CROSS JOIN LATERAL (
            SELECT price, currency FROM price_snapshot
            WHERE flight_id = f.id
            ORDER BY collected_at DESC
            LIMIT 1
        ) ps
        WHERE r.from_airport IN (:fromAirports) AND r.to_airport IN (:toAirports)
        AND r.active
        AND f.departure >= :from AND f.departure <= :to
        ORDER BY ps.price ASC
        """)
    List<FlightWithPrice> findDirectFlightsBetweenAirports(
        @Param("fromAirports") Iterable<String> fromAirports,
        @Param("toAirports") Iterable<String> toAirports,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
    // Flights whose departure has passed cannot be booked and are never searched - the search
    // window starts at today - so they are dead weight that would otherwise accumulate forever.
    @Modifying
    @Query("DELETE FROM flight WHERE departure < :cutoff")
    int deleteDepartedFlights(@Param("cutoff") Instant cutoff);

    // A flight with no price left is unreachable: every search reads a price through a LATERAL
    // join, so a flight without one can never appear in a result.
    @Modifying
    @Query("DELETE FROM flight f WHERE NOT EXISTS (SELECT 1 FROM price_snapshot ps WHERE ps.flight_id = f.id)")
    int deletePricelessFlights();

}
