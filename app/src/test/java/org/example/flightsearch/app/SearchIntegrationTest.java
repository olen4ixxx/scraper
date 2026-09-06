package org.example.flightsearch.app;

import org.example.flightsearch.app.service.SearchFormOptions;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.search.FlightSearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search, against a real PostgreSQL with real data in it.
 *
 * <p>Everything below depends on the database being PostgreSQL rather than merely SQL: the current
 * price of a flight comes from a LATERAL join, routes are ordered NULLS FIRST, saved searches are
 * claimed with ON CONFLICT. Substituting an in-memory database would test queries this application
 * never runs, so a container comes up instead. The migrations run against it too, which means they
 * are checked here as a side effect - the schema this asserts on is the one Flyway actually
 * produces.
 *
 * <p>On Windows with Docker Desktop, Testcontainers looks for a named pipe that Docker Desktop
 * does not serve on, and these are skipped rather than run. Point it at the right one for the
 * shell first if you want them locally:
 * {@code $env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"}. Nothing is needed on
 * the CI runners, where Docker is where Testcontainers expects it.
 *
 * <p>What is worth pinning is the handful of rules that decide whether a flight is offered at all,
 * because each has been wrong at some point and none of them announces itself when it is: prices
 * are converted from whatever the airline quoted, implausible ones are dropped after that
 * conversion rather than before, retired routes disappear, and a connection has to be somewhere
 * the traveller can actually change planes.
 */
@SpringBootTest(properties = {
    "collector.enabled=false",
    "spring.flyway.enabled=true"
})
@Testcontainers
@EnabledIf("dockerIsAvailable")
class SearchIntegrationTest {

    /**
     * Skipped rather than failed where there is no Docker to run PostgreSQL in. The rest of the
     * suite is pure and runs anywhere; making the whole of it depend on a container daemon would
     * mean "the tests are broken" on a laptop that simply has Docker closed.
     */
    static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable noDocker) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void useTheContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private FlightSearchService search;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SearchFormOptions formOptions;

    private static boolean seeded;

    /** Far enough out to sit inside any search window, and stable across the whole class. */
    private static final LocalDate DEPARTURE = LocalDate.now().plusDays(30);

    @BeforeAll
    static void resetSeed() {
        seeded = false;
    }

    private void seedOnce() {
        if (seeded) {
            return;
        }
        seeded = true;

        airport("WAW", "Warsaw", "Poland", "Europe/Warsaw");
        airport("BCN", "Barcelona", "Spain", "Europe/Madrid");
        airport("BUD", "Budapest", "Hungary", "Europe/Budapest");
        // An hour behind Poland, which is what makes the duration test below mean anything.
        airport("LTN", "London Luton", "United Kingdom", "Europe/London");
        airport("AGP", "Malaga", "Spain", "Europe/Madrid");

        // A plain euro fare, the ordinary case.
        long direct = route("RYANAIR", "WAW", "BCN", true);
        flight(direct, "FR1001", 8, 11, 49.99, "EUR");

        // The same trip priced as WizzAir prices it - in the departure market's currency. It has
        // to come back converted, or the graph and the headline would disagree about what a
        // flight costs. Storing it converted instead froze every fare at one day's rate.
        long inZloty = route("WIZZAIR", "WAW", "AGP", true);
        flight(inZloty, "W61001", 9, 13, 430.68, "PLN");

        // A sentinel price, of the kind a scraper leaves behind. Five zloty is small change and
        // five euros is a fare, so this can only be judged after conversion - which is why the
        // filter moved out of the SQL when prices stopped all being euros.
        long junk = route("RYANAIR", "WAW", "LTN", true);
        flight(junk, "FR9999", 7, 9, 4.00, "EUR");

        // A route the airline has stopped flying. Its old fares are still in the table and must
        // not be offered.
        long retired = route("RYANAIR", "WAW", "BUD", false);
        flight(retired, "FR7777", 6, 8, 39.99, "EUR");

        // Two legs that make a connection through Budapest, which is inside Schengen, and two
        // through Luton, which is not.
        long toBudapest = route("WIZZAIR", "WAW", "BUD", true);
        long budapestOnward = route("WIZZAIR", "BUD", "BCN", true);
        flight(toBudapest, "W62001", 6, 8, 120.0, "PLN");
        flight(budapestOnward, "W62002", 12, 15, 150.0, "EUR");

        long toLuton = route("RYANAIR", "WAW", "LTN", true);
        long lutonOnward = route("RYANAIR", "LTN", "BCN", true);
        flight(toLuton, "FR3001", 6, 8, 55.0, "EUR");
        flight(lutonOnward, "FR3002", 12, 15, 60.0, "EUR");
    }

    private void airport(String iata, String city, String country, String timezone) {
        jdbc.update("""
            INSERT INTO airport (iata, name, city, country, lat, lon, timezone)
            VALUES (?, ?, ?, ?, 0, 0, ?) ON CONFLICT (iata) DO NOTHING
            """, iata, city, city, country, timezone);
    }

    private long route(String airline, String from, String to, boolean active) {
        jdbc.update("""
            INSERT INTO route (airline, from_airport, to_airport, active)
            VALUES (?, ?, ?, ?) ON CONFLICT (airline, from_airport, to_airport) DO NOTHING
            """, airline, from, to, active);
        return jdbc.queryForObject(
            "SELECT id FROM route WHERE airline = ? AND from_airport = ? AND to_airport = ?",
            Long.class, airline, from, to);
    }

    private void flight(long routeId, String number, int departsAt, int arrivesAt, double price, String currency) {
        Instant departure = DEPARTURE.atTime(departsAt, 0).toInstant(java.time.ZoneOffset.UTC);
        Instant arrival = DEPARTURE.atTime(arrivesAt, 0).toInstant(java.time.ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO flight (route_id, flight_number, departure, arrival, updated_at)
            VALUES (?, ?, ?, ?, NOW())
            """, routeId, number, java.sql.Timestamp.from(departure), java.sql.Timestamp.from(arrival));
        Long flightId = jdbc.queryForObject(
            "SELECT id FROM flight WHERE route_id = ? AND flight_number = ?", Long.class, routeId, number);
        jdbc.update("""
            INSERT INTO price_snapshot (flight_id, price, currency, collected_at)
            VALUES (?, ?, ?, NOW())
            """, flightId, price, currency);
    }

    private List<SearchResult> searchFor(String from, String to, int maxStops, boolean schengenOnly) {
        seedOnce();
        return search.search(new SearchRequest(from, to, DEPARTURE, DEPARTURE, null, null, maxStops,
            Set.of(), SearchRequest.SortBy.CHEAPEST, null, null, null, null,
            true, false, false, false, null, schengenOnly));
    }

    @Test
    @DisplayName("the migrations produce the schema the search expects")
    void schemaIsWhatFlywayBuilt() throws Exception {
        seedOnce();
        // Counted from the classpath rather than written down, because a number written down is a
        // number to remember: this assertion said 6 while a seventh migration sat beside it, and
        // the build went red on CI for a bump nobody made. What it is actually here to catch is a
        // migration that has quietly stopped applying, and that it still catches.
        int onDisk = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/V*.sql").length;
        assertTrue(onDisk > 0, "the migrations should be on the test classpath at all");
        assertEquals(onDisk, jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class),
            "every migration on the classpath should have applied");

        // And the specific columns the code reads, so a migration that applies but does not do
        // what it says still fails here.
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'route' AND column_name IN ('active', 'last_attempted_at')
            """, Integer.class), "the columns collection orders and filters by");
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'airport' AND column_name = 'timezone'
            """, Integer.class), "without it a duration is two clocks subtracted from each other");
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'flight' AND column_name = 'time_known'
            """, Integer.class), "without it an invented clock cannot be told from a published one");
    }

    @Test
    @DisplayName("a fare quoted in another currency comes back in euros")
    void convertsToEuros() {
        List<SearchResult> results = searchFor("WAW", "AGP", 0, false);

        assertEquals(1, results.size());
        SearchResult found = results.get(0);
        assertEquals("EUR", found.currency());
        // 430.68 zloty is roughly a hundred euros. The exact figure moves with the daily rate,
        // so what is asserted is that a conversion happened at all - 430 would mean it did not.
        assertTrue(found.totalPrice() > 50 && found.totalPrice() < 200,
            "expected zloty converted to something euro-shaped, got " + found.totalPrice());
    }

    @Test
    @DisplayName("an implausibly cheap fare is dropped, and judged after conversion")
    void dropsSentinelPrices() {
        List<SearchResult> results = searchFor("WAW", "LTN", 0, false);

        assertTrue(results.stream().noneMatch(r -> r.totalPrice() < 5),
            "a four-euro fare is a leftover sentinel, not a ticket");
    }

    @Test
    @DisplayName("a route the airline has retired is not offered")
    void retiredRoutesDisappear() {
        List<SearchResult> results = searchFor("WAW", "BUD", 0, false);

        assertTrue(results.stream().noneMatch(r -> r.segments().get(0).flightId() != null
                && "FR7777".equals(r.segments().get(0).airline())),
            "its fares are still in the table; that is not a reason to sell them");
        assertTrue(results.stream().allMatch(r -> "WIZZAIR".equals(r.airlines().get(0))),
            "only the route still flown should answer");
    }

    @Test
    @DisplayName("a search that never touches Poland finds the flights we already hold")
    void searchesBetweenTwoOtherCountries() {
        // Budapest to Barcelona is one of the legs the connection tests below use, so it has been
        // in the database all along - and until the form let you name an origin outside Poland
        // there was no way to ask for it.
        List<SearchResult> results = searchFor("BUD", "BCN", 0, false);

        assertFalse(results.isEmpty(), "the leg is right there in the table");
        assertEquals("BUD", results.get(0).segments().get(0).fromAirport());
    }

    @Test
    @DisplayName("a country as an origin covers its airports, not just the ones Poland reaches")
    void aCountryOnEitherSideMeansItsAirports() {
        // The list behind COUNTRY: used to be "destinations reachable from Poland", which is a
        // set Warsaw itself is not in - it is only ever flown from here. Asking to leave from
        // anywhere in Poland found nothing at all.
        List<SearchResult> results = searchFor("COUNTRY:Poland", "BCN", 0, false);
        List<String> origins = results.stream()
            .map(r -> r.segments().get(0).fromAirport()).distinct().sorted().toList();

        assertFalse(results.isEmpty(), "Warsaw is in Poland and flies to Barcelona");
        assertTrue(origins.contains("WAW"), "expected a Warsaw departure, departed from " + origins);
    }

    @Test
    @DisplayName("the form offers every airport with a flight, in both directions")
    void theFormOffersOriginsAsWellAsDestinations() {
        seedOnce();
        List<String> offered = formOptions.airports().stream().map(AirportEntity::iata).toList();

        assertTrue(offered.contains("WAW"),
            "Warsaw only ever appears as a from_airport, and was missing from a list built out "
                + "of destinations - so the one place everything departs from could not be picked");
        assertTrue(offered.contains("BCN"), "and the destinations are still there");
    }

    @Test
    @DisplayName("a connection is found, and counted as one stop")
    void findsConnections() {
        List<SearchResult> results = searchFor("WAW", "BCN", 1, false);
        assertTrue(results.stream().anyMatch(r -> r.numberOfStops() == 1),
            "Warsaw to Barcelona is reachable by changing planes");
        assertTrue(results.stream().anyMatch(r -> r.numberOfStops() == 0),
            "and directly");
    }

    @Test
    @DisplayName("changing planes outside Schengen is excluded when it is not allowed")
    void schengenOnlyExcludesTheOthers() {
        List<SearchResult> connecting = searchFor("WAW", "BCN", 1, true).stream()
            .filter(r -> r.numberOfStops() == 1)
            .toList();

        assertFalse(connecting.isEmpty(), "Budapest is inside Schengen and should still be offered");
        assertTrue(connecting.stream().allMatch(r -> "BUD".equals(r.segments().get(0).toAirport())),
            "a change at Luton needs a visa this filter says the traveller does not have");
    }

    @Test
    @DisplayName("a flight across a time zone lasts as long as it really lasts")
    void durationsCrossTimeZones() {
        seedOnce();
        // Warsaw to Luton: airlines quote both ends in local time and the rows keep them that
        // way, so subtracting one clock from the other made this an hour shorter than the flight
        // is. Poland is an hour ahead of Britain, so 08:00 to 09:30 on the two clocks is two and
        // a half hours in the air.
        long crossing = route("WIZZAIR", "WAW", "LTN", true);
        flight(crossing, "W6ZONE", 8, 9, 89.0, "EUR");
        jdbc.update("UPDATE flight SET arrival = arrival + interval '30 minutes' WHERE flight_number = 'W6ZONE'");

        SearchResult found = search.search(new SearchRequest("WAW", "LTN", DEPARTURE, DEPARTURE, null, null,
                0, Set.of(), SearchRequest.SortBy.CHEAPEST, null, null, null, null,
                true, false, false, false, null, false)).stream()
            .filter(r -> r.totalPrice() == 89.0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the seeded crossing flight should be found"));

        assertEquals(150, found.duration().toMinutes(),
            "two clocks an hour apart subtracted from each other gives 90; the flight takes 150");
        assertTrue(found.duration().toMinutes() > 0, "and nothing should ever last a negative time");
    }

    @Test
    @DisplayName("a fare with no published times is still offered, but says so")
    void unpublishedTimesAreMarkedRatherThanHidden() {
        seedOnce();
        // WizzAir and Transavia publish a date and a price and no time of day. The rows stand in
        // for that with the ends of the day - 23:59 to 00:01 - which subtracts to minus
        // twenty-four hours and reached the page as "-23h -58m". The itinerary is still worth
        // showing; what it must not do is present an invented clock as a real one.
        long dateOnly = route("WIZZAIR", "WAW", "BCN", true);
        jdbc.update("""
            INSERT INTO flight (route_id, flight_number, departure, arrival, updated_at, time_known)
            VALUES (?, 'W6DATEONLY', ?, ?, NOW(), FALSE)
            """, dateOnly,
            java.sql.Timestamp.from(DEPARTURE.atTime(23, 59).toInstant(java.time.ZoneOffset.UTC)),
            java.sql.Timestamp.from(DEPARTURE.atTime(0, 1).toInstant(java.time.ZoneOffset.UTC)));
        jdbc.update("""
            INSERT INTO price_snapshot (flight_id, price, currency, collected_at)
            SELECT id, 77.0, 'EUR', NOW() FROM flight WHERE flight_number = 'W6DATEONLY'
            """);

        SearchResult found = searchFor("WAW", "BCN", 0, false).stream()
            .filter(r -> r.totalPrice() == 77.0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("a date-only fare should still be offered"));

        assertFalse(found.timesPublished(), "it has to be marked, or the page cannot warn about it");
        assertFalse(found.duration().isNegative(), "nothing lasts a negative time");
        assertEquals(Duration.ZERO, found.duration(),
            "an unknown duration is unknown - the page shows nothing rather than a number");
    }

    @Test
    @DisplayName("shortest-first does not reward flights whose length nobody knows")
    void unknownDurationsSortLast() {
        seedOnce();
        List<SearchResult> byLength = search.search(new SearchRequest("WAW", "BCN", DEPARTURE, DEPARTURE,
            null, null, 0, Set.of(), SearchRequest.SortBy.SHORTEST, null, null, null, null,
            true, false, false, false, null, false));

        int firstUnknown = -1;
        int lastKnown = -1;
        for (int i = 0; i < byLength.size(); i++) {
            if (byLength.get(i).timesPublished()) {
                lastKnown = i;
            } else if (firstUnknown < 0) {
                firstUnknown = i;
            }
        }
        if (firstUnknown >= 0 && lastKnown >= 0) {
            assertTrue(firstUnknown > lastKnown,
                "left to sort naturally these won every shortest-first search, because a fabricated "
                    + "duration is shorter than any real one");
        }
    }

    @Test
    @DisplayName("a wide search over a full network answers quickly enough to be a web page")
    void staysFastOverALargeNetwork() {
        seedOnce();
        seedBulk(4000);

        long startedAt = System.currentTimeMillis();
        List<SearchResult> results = search.search(new SearchRequest("POLAND", "ANYWHERE",
            DEPARTURE, DEPARTURE.plusDays(7), null, null, 1, Set.of(),
            SearchRequest.SortBy.CHEAPEST, null, null, null, null,
            true, false, false, false, null, false));
        long took = System.currentTimeMillis() - startedAt;

        assertFalse(results.isEmpty(), "a search over a seeded network should find something");
        assertTrue(results.size() <= 500, "results are capped so the page stays a page, got " + results.size());
        // A guard against an accidental N+1 or a dropped index rather than a benchmark, so the
        // bound is three times what this actually takes - about 2.5 seconds for 500 results out
        // of sixteen thousand flights - and still far short of the two minutes a search cost when
        // every flight's route and airport were looked up one round trip at a time.
        assertTrue(took < 8_000, "a wide search took " + took + "ms, which is not a web page any more");
    }

    /**
     * A network large enough for the query plan to matter rather than a handful of rows.
     *
     * <p>On its own airports, deliberately. Seeded onto the routes the tests above search, its
     * sixteen thousand cheap fares simply crowded them out - results are sorted by price and
     * capped at five hundred, so a connection at 115 euros vanished behind bulk fares at twenty
     * and two tests failed or passed depending on which ran first. Shared state between tests in
     * one class is only safe when they cannot see each other's data.
     */
    private void seedBulk(int flightsPerRoute) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM flight", Integer.class) > 1000) {
            return;
        }
        airport("KRK", "Krakow", "Poland", "Europe/Warsaw");
        airport("KTW", "Katowice", "Poland", "Europe/Warsaw");
        airport("VLC", "Valencia", "Spain", "Europe/Madrid");
        airport("ALC", "Alicante", "Spain", "Europe/Madrid");

        long[] bulkRoutes = {
            route("RYANAIR", "KRK", "VLC", true),
            route("RYANAIR", "KRK", "ALC", true),
            route("RYANAIR", "KTW", "VLC", true),
            route("WIZZAIR", "KTW", "ALC", true)
        };
        for (long routeId : bulkRoutes) {
            for (int f = 0; f < flightsPerRoute; f++) {
                Instant departure = DEPARTURE.plusDays(f % 7).atTime(6 + (f % 12), 0)
                    .toInstant(java.time.ZoneOffset.UTC).plus(f, ChronoUnit.SECONDS);
                jdbc.update("""
                    INSERT INTO flight (route_id, flight_number, departure, arrival, updated_at)
                    VALUES (?, ?, ?, ?, NOW())
                    """, routeId, "BULK" + routeId + "_" + f, java.sql.Timestamp.from(departure),
                    java.sql.Timestamp.from(departure.plus(3, ChronoUnit.HOURS)));
            }
        }
        // One price each, which is what a flight has once the history cap has done its work.
        jdbc.update("""
            INSERT INTO price_snapshot (flight_id, price, currency, collected_at)
            SELECT f.id, 20 + (f.id % 180), 'EUR', NOW()
            FROM flight f
            WHERE NOT EXISTS (SELECT 1 FROM price_snapshot ps WHERE ps.flight_id = f.id)
            """);
    }
}
