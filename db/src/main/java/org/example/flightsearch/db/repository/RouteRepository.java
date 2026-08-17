package org.example.flightsearch.db.repository;

import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.entity.RouteEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
    // Longest-untried first, because a run does not always reach the end of the list: airlines
    // meter what they will answer (WizzAir allows roughly fifty requests a minute, and a full
    // pass over its network is four and a half thousand), so a run can legitimately run out of
    // time. In a fixed order that means the same head of the list is refreshed every few hours
    // while the tail is never collected at all. Ordered this way, whatever was missed last time
    // is what gets collected first this time, and coverage comes round evenly.
    //
    // On last attempt rather than last success, deliberately - see V3__route_last_attempted.sql.
    // A route that yields nothing would otherwise never stop sorting first and would starve the
    // routes that do have flights.
    @Query("""
        SELECT * FROM route
        WHERE airline = :airline AND active
        ORDER BY last_attempted_at ASC NULLS FIRST, id
        """)
    List<RouteEntity> findByAirline(@Param("airline") Airline airline);

    @Modifying
    @Query("UPDATE route SET last_attempted_at = :attemptedAt WHERE id = :id")
    void markAttempted(@Param("id") Long id, @Param("attemptedAt") Instant attemptedAt);

    // Retiring a route rather than deleting it. An airline's network changes with the season,
    // and a route absent from one rediscovery may well be back in the next - deleting would
    // throw away its price history for good, while this is a flag that flips back. Collection
    // skips retired routes, and so does search, so they cost nothing until they return.
    @Modifying
    @Query("UPDATE route SET active = FALSE WHERE airline = :airline AND active AND id NOT IN (:keepIds)")
    int retireRoutesOtherThan(@Param("airline") Airline airline, @Param("keepIds") Iterable<Long> keepIds);

    @Modifying
    @Query("UPDATE route SET active = TRUE WHERE id IN (:ids) AND NOT active")
    int reinstate(@Param("ids") Iterable<Long> ids);

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
