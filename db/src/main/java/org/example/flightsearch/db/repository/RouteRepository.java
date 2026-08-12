package org.example.flightsearch.db.repository;

import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.entity.RouteEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RouteRepository extends CrudRepository<RouteEntity, Long> {
    
    Optional<RouteEntity> findByAirlineAndFromAirportAndToAirport(
        Airline airline, String fromAirport, String toAirport
    );
    
    @Query("SELECT * FROM route WHERE from_airport = :airport OR to_airport = :airport")
    Iterable<RouteEntity> findByAirport(@Param("airport") String airport);
}
