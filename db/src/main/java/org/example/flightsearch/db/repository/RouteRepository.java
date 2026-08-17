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

    // Lets a scheduled collection reuse the network found by an earlier run rather than
    // rediscovering it - which for some airlines means ten thousand requests to learn nothing new.
    // Longest-unvisited first, because a run does not always reach the end of the list: airlines
    // meter what they will answer (WizzAir allows roughly fifty requests a minute, and a full
    // pass over its network is four and a half thousand), so a run can legitimately run out of
    // time. In a fixed order that means the same head of the list is refreshed every few hours
    // while the tail is never collected at all. Ordered this way, whatever was missed last time
    // is what gets collected first this time, and coverage comes round evenly.
    @Query("""
        SELECT r.* FROM route r
        LEFT JOIN LATERAL (
            SELECT MAX(ps.collected_at) AS last_collected
            FROM flight f
            JOIN price_snapshot ps ON ps.flight_id = f.id
            WHERE f.route_id = r.id
        ) lc ON TRUE
        WHERE r.airline = :airline
        ORDER BY lc.last_collected ASC NULLS FIRST, r.id
        """)
    List<RouteEntity> findByAirline(@Param("airline") Airline airline);

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
