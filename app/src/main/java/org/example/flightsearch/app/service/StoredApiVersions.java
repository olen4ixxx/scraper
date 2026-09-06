package org.example.flightsearch.app.service;

import org.example.flightsearch.collector.ApiVersionStore;
import org.example.flightsearch.db.repository.CollectorSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The note one collection run leaves for the next, kept in the database because that is the only
 * thing about a run that outlives it.
 *
 * <p>Reading is deliberately forgiving: a version that cannot be read is not a reason to fail a
 * pass, because there is a compiled-in default to fall back to and a search that will find the
 * right one anyway. Writing is not forgiving here - the collector decides what to do about a note
 * that will not stick, and it decides to carry on.
 */
@Service
public class StoredApiVersions implements ApiVersionStore {
    private static final Logger logger = LoggerFactory.getLogger(StoredApiVersions.class);

    /** One row per airline, named so a person reading the table can tell what it is. */
    private static final String KEY_PREFIX = "api-version.";

    private final CollectorSettingRepository settings;

    public StoredApiVersions(CollectorSettingRepository settings) {
        this.settings = settings;
    }

    @Override
    public Optional<String> current(String airline) {
        try {
            return settings.findValue(KEY_PREFIX + airline.toLowerCase());
        } catch (Exception e) {
            logger.warn("Could not read the stored API version for {}, so the configured one is "
                + "used: {}", airline, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void remember(String airline, String version) {
        settings.put(KEY_PREFIX + airline.toLowerCase(), version);
        logger.info("Recorded {} as the API version for {}", version, airline);
    }
}
