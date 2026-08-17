package org.example.flightsearch.app.service;

import org.example.flightsearch.common.dto.SearchRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 * one gets a number after it.
 *
 * <p>Held in memory and bounded, oldest dropped first. A restart therefore forgets them, and a
 * link that outlives the process resolves to nothing - the results page sends those back to the
 * search form rather than showing an error. That is the trade for not putting a table behind
 * something whose whole job is to make an address shorter.
 */
@Service
public class SavedSearches {
    /**
     * Enough that a session's worth of searching stays reachable by the back button, small enough
     * that nothing has to be evicted on a timer.
     */
    private static final int REMEMBERED = 500;
    private static final DateTimeFormatter DAY_AND_MONTH = DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH);

    private final Map<String, SearchRequest> byName = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SearchRequest> eldest) {
            return size() > REMEMBERED;
        }
    };

    public synchronized String save(SearchRequest request) {
        String base = name(request);
        String name = base;
        for (int suffix = 2; byName.containsKey(name) && !request.equals(byName.get(name)); suffix++) {
            name = base + "-" + suffix;
        }
        byName.put(name, request);
        return name;
    }

    public synchronized Optional<SearchRequest> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    private static String name(SearchRequest request) {
        String from = slug(request.from());
        String to = slug(firstDestination(request.to()));
        String when = request.departure().format(DAY_AND_MONTH).toLowerCase(Locale.ENGLISH);
        return from + "-" + to + "-" + when;
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
        return cleaned.isEmpty() ? "any" : cleaned;
    }
}
