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
}
