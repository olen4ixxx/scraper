package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.SavedSearchEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SavedSearchRepository extends CrudRepository<SavedSearchEntity, String> {

    @Query("SELECT request FROM saved_search WHERE name = :name")
    Optional<String> findRequestByName(@Param("name") String name);

    /**
     * Written explicitly rather than through save(), because the name is the key: an entity with
     * a non-null id reads as an existing row to Spring Data JDBC, which would make every new
     * search an UPDATE of nothing. DO NOTHING on conflict also settles the race between two
     * people running the same search at once - whoever loses simply finds the name taken and
     * checks whether it holds their search.
     *
     * @return 1 when the name was claimed, 0 when somebody else already holds it
     */
    @Modifying
    @Query("""
        INSERT INTO saved_search (name, request, created_at, last_used_at)
        VALUES (:name, :request, NOW(), NOW())
        ON CONFLICT (name) DO NOTHING
        """)
    int claim(@Param("name") String name, @Param("request") String request);

    @Modifying
    @Query("UPDATE saved_search SET last_used_at = NOW() WHERE name = :name")
    int markUsed(@Param("name") String name);

    /** Housekeeping: a name nobody has followed in a long time is not worth keeping an address for. */
    @Modifying
    @Query("DELETE FROM saved_search WHERE last_used_at < :cutoff")
    int deleteUnusedSince(@Param("cutoff") Instant cutoff);
}
