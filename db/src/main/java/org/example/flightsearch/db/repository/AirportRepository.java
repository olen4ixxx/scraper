package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.AirportEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AirportRepository extends CrudRepository<AirportEntity, Long> {
    
    Optional<AirportEntity> findByIata(String iata);
    
    @Query("SELECT * FROM airport WHERE iata IN (:iataCodes)")
    Iterable<AirportEntity> findByIataIn(@Param("iataCodes") Iterable<String> iataCodes);

    @Query("""
        SELECT DISTINCT a.* FROM airport a
        JOIN route r ON r.to_airport = a.iata
        JOIN flight f ON f.route_id = r.id
        WHERE r.from_airport IN (:fromAirports)
        ORDER BY a.country, a.city
        """)
    Iterable<AirportEntity> findDestinationsFrom(@Param("fromAirports") Iterable<String> fromAirports);

    /**
     * Every airport with a flight on either side of it - the places a search can start from as
     * well as the places it can reach.
     *
     * <p>The form used to be offered only what is reachable from Poland, because that is where
     * the collection starts. But the flights collected are whole airline networks, not just the
     * Polish part of them: Milan to Barcelona is in the database whether or not anyone in Warsaw
     * cares. Offering only the Polish half hid data that was already there.
     *
     * <p>Two exists-driven halves rather than one join over flights: the route table is small and
     * the flight table is not, so this asks whether a route has any flight at all instead of
     * building a row per flight and then throwing the duplicates away.
     */
    @Query("""
        SELECT * FROM airport a
        WHERE a.iata IN (
            SELECT r.from_airport FROM route r
            WHERE EXISTS (SELECT 1 FROM flight f WHERE f.route_id = r.id)
            UNION
            SELECT r.to_airport FROM route r
            WHERE EXISTS (SELECT 1 FROM flight f WHERE f.route_id = r.id)
        )
        ORDER BY a.country, a.city
        """)
    Iterable<AirportEntity> findAirportsWithFlights();
}
