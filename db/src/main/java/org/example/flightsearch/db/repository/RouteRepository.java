package org.example.flightsearch.db.repository;

import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.entity.RouteEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteRepository extends CrudRepository<RouteEntity, Long> {
    
    Optional<RouteEntity> findByAirlineAndFromAirportAndToAirport(
        Airline airline, String fromAirport, String toAirport
    );
    
    @Query("SELECT * FROM route WHERE from_airport = :airport OR to_airport = :airport")
    Iterable<RouteEntity> findByAirport(@Param("airport") String airport);

    // Drives the airline filter on the search form. Requiring an actual flight keeps airlines
    // whose routes exist but were never priced (or that another tool has yet to collect) from
    // showing up as a filter that can only ever return nothing.
    @Query("""
        SELECT DISTINCT r.airline FROM route r
        WHERE EXISTS (SELECT 1 FROM flight f WHERE f.route_id = r.id)
        ORDER BY r.airline
        """)
    List<Airline> findAirlinesWithFlights();
}
