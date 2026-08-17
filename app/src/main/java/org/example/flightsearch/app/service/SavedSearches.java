package org.example.flightsearch.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.db.repository.SavedSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a search into a short name it can be reached by, so the results page has an address
 * worth looking at.
 *
 * <p>A search carries nineteen parameters, and putting them all in the query string made a URL
 * some four hundred characters long - unreadable, unmemorable, and impossible to send to anyone
 * without it wrapping across three lines. Keeping them here instead leaves the browser showing
 * "/results/poland-anywhere-06sep".
 *
 * <p>The name is built from the parts of a search someone would actually recognise - where from,
 * where to, and when - rather than a random string, so the address still says what it is. Two
 * different searches can agree on all three (same route and date, different filters); the second
 * one gets a number after it, and a search identical to one already named simply gets that name
 * back rather than a second one.
 *
 * <p>Stored in the database rather than in memory. In memory a link stopped working the moment
 * the process restarted, which on this hosting means every deploy and every idle period - a
 * link sent to someone in the morning would not open in the afternoon, which rather defeats
 * having a short address to send.
 */
@Service
public class SavedSearches {
    private static final Logger logger = LoggerFactory.getLogger(SavedSearches.class);
    private static final DateTimeFormatter DAY_AND_MONTH = DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH);
    /** Enough attempts to get past a genuinely contested name without spinning on a fault. */
    private static final int NAME_ATTEMPTS = 50;

    private final SavedSearchRepository repository;
    private final ObjectMapper mapper;

    public SavedSearches(SavedSearchRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public String save(SearchRequest request) {
        String json;
        try {
            json = mapper.writeValueAsString(request);
        } catch (Exception e) {
            // Without a stored search there is no name to give out, and the caller needs one.
            throw new IllegalStateException("Could not store the search", e);
        }

        String base = name(request);
        for (int attempt = 1; attempt <= NAME_ATTEMPTS; attempt++) {
            String candidate = attempt == 1 ? base : base + "-" + attempt;
            if (repository.claim(candidate, json) == 1) {
                return candidate;
            }
            // Taken - by this very search, in which case reuse it, or by a different one, in
            // which case try the next number along.
            if (find(candidate).filter(request::equals).isPresent()) {
                repository.markUsed(candidate);
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free name for the search starting from " + base);
    }

    public Optional<SearchRequest> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repository.findRequestByName(name).flatMap(this::parse);
    }

    /** Marks a name as still in use, so housekeeping keeps the addresses people actually follow. */
    public void markUsed(String name) {
        try {
            repository.markUsed(name);
        } catch (Exception e) {
            // Nothing here is worth failing a page view over.
            logger.debug("Could not mark search {} as used: {}", name, e.getMessage());
        }
    }

    private Optional<SearchRequest> parse(String json) {
        try {
            return Optional.of(mapper.readValue(json, SearchRequest.class));
        } catch (Exception e) {
            // A row written by an older version whose fields have since changed. The name simply
            // doesn't resolve, and the results page sends the visitor to the search form.
            logger.warn("Stored search could not be read back, treating it as gone: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String name(SearchRequest request) {
        return slug(request.from())
            + "-" + slug(firstDestination(request.to()))
            + "-" + request.departure().format(DAY_AND_MONTH).toLowerCase(Locale.ENGLISH);
    }

    /** A search can list several destinations; the first one is the one worth naming it after. */
    private static String firstDestination(String to) {
        if (to == null || to.isBlank()) {
            return "anywhere";
        }
        String first = to.split(",")[0].trim();
        int marker = first.indexOf(':');
        return marker >= 0 ? first.substring(marker + 1) : first;
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "any";
        }
        String cleaned = value.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "");
        if (cleaned.isEmpty()) {
            return "any";
        }
        // The column holds 120 characters and three of these go into a name; a country typed out
        // in full has no business using all of it.
        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }
}
