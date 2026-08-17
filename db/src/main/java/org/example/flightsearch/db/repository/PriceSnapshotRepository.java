package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.PriceSnapshotEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
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

    // Goes before the flights themselves - a snapshot references its flight, so the flight
    // cannot be removed while its prices are still pointing at it.
    @Modifying
    @Query("""
        DELETE FROM price_snapshot ps
        USING flight f
        WHERE ps.flight_id = f.id AND f.departure < :cutoff
        """)
    int deleteForDepartedFlights(@Param("cutoff") Instant cutoff);
}
