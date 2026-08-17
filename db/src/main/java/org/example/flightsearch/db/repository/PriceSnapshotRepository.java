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

    /**
     * Keeps the most recent price points for each flight and drops the rest.
     *
     * <p>Recording only price changes stopped the database growing by three quarters of a million
     * rows a day, but it did not stop it growing: Ryanair alone genuinely moves about 135,000
     * prices a day, and a flight that lives out its 60 days in the search window accumulates
     * something like 67 of them. Without a limit that is roughly 17MB a day against a 540MB
     * allowance - fine for three weeks, and then not.
     *
     * <p>Trimming to the newest points keeps what a price graph is actually for. What is lost is
     * the far end of a long-lived flight's history, which is the part nobody is deciding anything
     * on.
     */
    @Modifying
    @Query("""
        DELETE FROM price_snapshot
        WHERE id IN (
            SELECT id FROM (
                SELECT id, ROW_NUMBER() OVER (
                    PARTITION BY flight_id ORDER BY collected_at DESC, id DESC
                ) AS recency
                FROM price_snapshot
            ) ranked
            WHERE recency > :keep
        )
        """)
    int trimHistoryToNewest(@Param("keep") int keep);
}
