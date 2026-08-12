package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.PriceSnapshotEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PriceSnapshotRepository extends CrudRepository<PriceSnapshotEntity, Long> {

    @Query("""
        SELECT * FROM price_snapshot
        WHERE flight_id = :flightId
        ORDER BY collected_at DESC
        LIMIT 1
        """)
    Optional<PriceSnapshotEntity> findLatestByFlightId(@Param("flightId") Long flightId);

    @Query("SELECT * FROM price_snapshot WHERE flight_id = :flightId ORDER BY collected_at")
    List<PriceSnapshotEntity> findByFlightIdOrderByCollectedAt(@Param("flightId") Long flightId);
}
