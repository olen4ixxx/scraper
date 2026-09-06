package org.example.flightsearch.db.repository;

import org.example.flightsearch.db.entity.CollectorSettingEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CollectorSettingRepository extends CrudRepository<CollectorSettingEntity, String> {

    @Query("SELECT value FROM collector_setting WHERE name = :name")
    Optional<String> findValue(@Param("name") String name);

    /**
     * Written out rather than through save(), because the name is the key: an entity with a
     * non-null id reads as an existing row to Spring Data JDBC, so every first write would be an
     * update of nothing. The upsert also settles two collection jobs discovering the same new
     * version at the same time - they agree, so whoever lands second simply confirms it.
     */
    @Modifying
    @Query("""
        INSERT INTO collector_setting (name, value, updated_at)
        VALUES (:name, :value, NOW())
        ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
        """)
    void put(@Param("name") String name, @Param("value") String value);
}
