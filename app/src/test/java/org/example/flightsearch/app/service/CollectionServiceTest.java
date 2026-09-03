package org.example.flightsearch.app.service;

import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.CollectionRefusedException;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.entity.RouteEntity;
import org.example.flightsearch.db.repository.FlightRepository;
import org.example.flightsearch.db.repository.PriceSnapshotRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.example.flightsearch.db.repository.SavedSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How a collection pass decides to stop, and what it reports when it does.
 *
 * <p>Every long silence this project has had lived in this class or was let through by it: a pass
 * that walked its whole network collecting nothing and called that success, a budget that never
 * fired, an abandon-on-refusal that had never once worked. The last two shared a cause worth
 * remembering - the checks were made when a route was queued rather than when it ran, and since
 * all of them are queued in the first milliseconds of a pass, every check was made against a
 * deadline still hours away. They passed, the whole queue went through, and nothing stopped.
 */
class CollectionServiceTest {

    private RouteRepository routes;
    private FlightRepository flights;
    private PriceSnapshotRepository prices;
    private SavedSearchRepository savedSearches;
    private RoutePersistenceService persistence;

    /** A collector that answers instantly and counts how many routes it was actually asked about. */
    private static class CountingCollector implements AirlineCollector {
        final AtomicInteger asked = new AtomicInteger();
        private final Function<RouteDto, List<FlightDto>> answer;

        CountingCollector(Function<RouteDto, List<FlightDto>> answer) {
            this.answer = answer;
        }

        @Override public Airline airline() { return Airline.WIZZAIR; }
        @Override public List<RouteDto> loadRoutes() { return List.of(); }

        @Override
        public List<FlightDto> loadFlights(RouteDto route) {
            asked.incrementAndGet();
            return answer.apply(route);
        }
    }

    private static final List<FlightDto> ONE_FARE = List.of(
        new FlightDto("N/A", LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(7), 49.0, "EUR"));

    @BeforeEach
    void setUp() {
        routes = mock(RouteRepository.class);
        flights = mock(FlightRepository.class);
        prices = mock(PriceSnapshotRepository.class);
        savedSearches = mock(SavedSearchRepository.class);
        persistence = mock(RoutePersistenceService.class);
    }

    /** Stored routes, all long untried, between airports the reference dataset knows. */
    private void storedRoutes(int count) {
        List<RouteEntity> stored = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            RouteEntity route = new RouteEntity(id, Airline.WIZZAIR, "WAW", "BCN", true, null);
            stored.add(route);
            when(persistence.ensureRoute(any(), any(), any())).thenReturn(route);
        }
        when(routes.findByAirline(Airline.WIZZAIR)).thenReturn(stored);
        // Mirrors the real thing: it reports how many fares it was handed, so a collector that
        // returns nothing leaves the pass with nothing collected - which is the whole point of
        // the empty-pass check below.
        when(persistence.saveFlights(anyLong(), any())).thenAnswer(call -> ((List<?>) call.getArgument(1)).size());
    }

    private CollectionService serviceWith(AirlineCollector collector, long budgetMinutes) {
        return new CollectionService(List.of(collector), new AirportResolver(), persistence,
            routes, flights, prices, savedSearches, budgetMinutes, 4);
    }

    @Test
    @DisplayName("a pass out of time stops asking, and does not call that a failure")
    void stopsWhenTheBudgetIsSpent() {
        // The check has to happen when a route gets its turn. Made at queueing time it tests a
        // deadline that is always still ahead, lets the entire queue through, and stops nothing -
        // which is what it did, and why a WizzAir job died at the curl timeout every single run
        // rather than finishing early with what it had.
        storedRoutes(200);
        CountingCollector collector = new CountingCollector(route -> ONE_FARE);

        CollectionService service = serviceWith(collector, 0);
        assertDoesNotThrow(() -> service.collectAirline(Airline.WIZZAIR),
            "running out of time is a normal way for a pass to end, not a fault");

        assertTrue(collector.asked.get() < 10,
            "a spent budget should stop the pass, but it asked about " + collector.asked.get() + " routes");
    }

    @Test
    @DisplayName("a pass being refused stops rather than sending the rest of the network")
    void stopsWhenRefused() {
        storedRoutes(200);
        CountingCollector collector = new CountingCollector(route -> {
            throw new CollectionRefusedException(Airline.WIZZAIR, 25, "403 Forbidden");
        });

        CollectionService service = serviceWith(collector, 60);
        assertThrows(RuntimeException.class, () -> service.collectAirline(Airline.WIZZAIR),
            "being turned away should fail the run, so the job goes red and someone looks");

        assertTrue(collector.asked.get() < 200,
            "the remaining routes should be dropped, not sent; it asked about " + collector.asked.get());
    }

    @Test
    @DisplayName("a pass that asks about routes and gets no fares at all is a failure")
    void emptyPassFails() {
        // Both silences looked exactly like this from the outside: routes visited, nothing
        // collected, run reported green. WizzAir behind a moved API version, Transavia behind a
        // changed response shape.
        storedRoutes(60);
        CountingCollector collector = new CountingCollector(route -> List.of());

        CollectionService service = serviceWith(collector, 60);
        Exception failure = assertThrows(RuntimeException.class, () -> service.collectAirline(Airline.WIZZAIR));

        assertTrue(rootCauseOf(failure).getMessage().contains("not one fare"),
            "the failure should say what is wrong, not just that something is: " + rootCauseOf(failure).getMessage());
        assertEquals(60, collector.asked.get(), "it should still have asked before concluding anything");
    }

    @Test
    @DisplayName("a thin pass with no fares is not called broken")
    void aFewEmptyRoutesAreNotAFailure() {
        // Three empty routes really can all be empty. The judgement needs a sample before it is
        // worth making, or an airline with a handful of stale routes would fail every run.
        storedRoutes(3);
        CountingCollector collector = new CountingCollector(route -> List.of());

        CollectionService service = serviceWith(collector, 60);
        assertDoesNotThrow(() -> service.collectAirline(Airline.WIZZAIR));
    }

    @Test
    @DisplayName("routes asked about recently are left alone, so an interrupted pass resumes")
    void skipsRoutesAlreadyAskedAboutRecently() {
        RouteEntity justDone = new RouteEntity(1L, Airline.WIZZAIR, "WAW", "BCN", true, Instant.now());
        when(routes.findByAirline(Airline.WIZZAIR)).thenReturn(List.of(justDone));
        when(persistence.ensureRoute(any(), any(), any())).thenReturn(justDone);

        CountingCollector collector = new CountingCollector(route -> ONE_FARE);
        CollectionService service = serviceWith(collector, 60);
        service.collectAirline(Airline.WIZZAIR);

        assertEquals(0, collector.asked.get(),
            "a route collected minutes ago should not be collected again on the next pass");
    }

    private static Throwable rootCauseOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
