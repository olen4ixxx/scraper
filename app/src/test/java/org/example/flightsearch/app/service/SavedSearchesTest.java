package org.example.flightsearch.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.entity.SavedSearchEntity;
import org.example.flightsearch.db.repository.SavedSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The short names the results page is reached by.
 *
 * <p>Two things here are easy to get wrong and invisible when wrong. A search repeated must come
 * back with the name it already has, or every reload would mint another address. And two searches
 * that differ only in the order their airlines happen to iterate in must count as one search -
 * they are compared by value, and an unordered set made identical searches look different, which
 * is why the airlines are collected into an EnumSet.
 */
class SavedSearchesTest {

    private SavedSearches searches;

    /** Enough of the repository to behave like the table: names are unique and first claim wins. */
    private static class InMemorySearches implements SavedSearchRepository {
        private final Map<String, String> byName = new HashMap<>();

        @Override
        public Optional<String> findRequestByName(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public int claim(String name, String request) {
            return byName.putIfAbsent(name, request) == null ? 1 : 0;
        }

        @Override
        public int markUsed(String name) {
            return byName.containsKey(name) ? 1 : 0;
        }

        @Override
        public int deleteUnusedSince(Instant cutoff) {
            return 0;
        }

        // Nothing below is used: a saved search is only ever claimed and read back by name.
        @Override public <S extends SavedSearchEntity> S save(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends SavedSearchEntity> Iterable<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<SavedSearchEntity> findById(String name) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String name) { throw new UnsupportedOperationException(); }
        @Override public Iterable<SavedSearchEntity> findAll() { throw new UnsupportedOperationException(); }
        @Override public Iterable<SavedSearchEntity> findAllById(Iterable<String> names) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(String name) { throw new UnsupportedOperationException(); }
        @Override public void delete(SavedSearchEntity entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> names) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends SavedSearchEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
    }

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        searches = new SavedSearches(new InMemorySearches(), mapper);
    }

    private static SearchRequest search(String from, String to, String departure,
                                        Set<Airline> airlines, int maxStops) {
        return new SearchRequest(from, to, LocalDate.parse(departure), null, null, null, maxStops,
            airlines, SearchRequest.SortBy.CHEAPEST, null, null, null, null,
            false, false, false, false, null, false);
    }

    @Test
    @DisplayName("the name says what the search is")
    void namesReadAsTheSearch() {
        assertEquals("waw-bcn-12oct", searches.save(search("WAW", "BCN", "2026-10-12", Set.of(), 1)));
        assertEquals("poland-anywhere-06sep", searches.save(search("POLAND", "ANYWHERE", "2026-09-06", Set.of(), 1)));
    }

    @Test
    @DisplayName("a city or country destination is named by the place, not by the prefix")
    void stripsDestinationPrefixes() {
        assertEquals("krk-rome-03nov", searches.save(search("KRK", "CITY:Rome", "2026-11-03", Set.of(), 1)));
        assertEquals("poland-italy-24dec", searches.save(search("POLAND", "COUNTRY:Italy", "2026-12-24", Set.of(), 1)));
    }

    @Test
    @DisplayName("several destinations are named after the first")
    void namesAfterTheFirstDestination() {
        assertEquals("waw-bcn-12oct", searches.save(search("WAW", "BCN,MAD,VLC", "2026-10-12", Set.of(), 1)));
    }

    @Test
    @DisplayName("the same search keeps the name it already has")
    void repeatingASearchReusesItsName() {
        String first = searches.save(search("WAW", "BCN", "2026-10-12", Set.of(), 1));
        String again = searches.save(search("WAW", "BCN", "2026-10-12", Set.of(), 1));

        assertEquals(first, again, "reloading a search should not mint a second address for it");
    }

    @Test
    @DisplayName("the order airlines happen to come in does not make it a different search")
    void airlineOrderDoesNotMatter() {
        // A HashSet iterates unpredictably. If that reached the comparison, the same search
        // submitted twice would sometimes be handed a second name with -2 on the end.
        Set<Airline> oneWay = new HashSet<>(List.of(Airline.RYANAIR, Airline.WIZZAIR));
        Set<Airline> theOther = EnumSet.of(Airline.WIZZAIR, Airline.RYANAIR);

        assertEquals(searches.save(search("WAW", "BCN", "2026-10-12", oneWay, 1)),
                     searches.save(search("WAW", "BCN", "2026-10-12", theOther, 1)));
    }

    @Test
    @DisplayName("a different search on the same route and day gets its own name")
    void differentFiltersGetTheirOwnName() {
        String direct = searches.save(search("WAW", "BCN", "2026-10-12", Set.of(), 0));
        String withStops = searches.save(search("WAW", "BCN", "2026-10-12", Set.of(), 1));

        assertNotEquals(direct, withStops);
        assertTrue(withStops.startsWith("waw-bcn-12oct"), "still recognisable, just distinguished");
    }

    @Test
    @DisplayName("a name reads back as the search it was given")
    void roundTrips() {
        SearchRequest request = search("KRK", "CITY:Rome", "2026-11-03", EnumSet.of(Airline.RYANAIR), 1);
        String name = searches.save(request);

        assertEquals(Optional.of(request), searches.find(name));
    }

    @Test
    @DisplayName("a name nobody saved resolves to nothing rather than failing")
    void unknownNamesAreEmpty() {
        assertEquals(Optional.empty(), searches.find("never-existed-01jan"));
        assertEquals(Optional.empty(), searches.find(null));
        assertEquals(Optional.empty(), searches.find(""));
    }
}
