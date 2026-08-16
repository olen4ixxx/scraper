package org.example.flightsearch.collector.volotea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volotea's published timetable, one static file per city pair. The booking site itself sits
 * behind Imperva and refuses any non-browser client, but these files are served from a plain
 * bucket on a different host that asks for nothing at all - not even a user agent.
 *
 * <p>This is the richest of the sources here and the only one that needs no compromises: real
 * departure and arrival times, real flight numbers, an explicit currency, and a price per
 * dated flight rather than per day or per window. Nothing has to be invented or approximated.
 *
 * <p>One file covers a pair in both directions, which is also how routes are discovered:
 * there is no route list anywhere, but a pair they don't fly is simply a missing file. So a
 * single request both answers "is this a route" and returns its whole timetable, and the
 * schedules parsed during discovery are kept for {@link #loadFlights} instead of fetching
 * every file a second time.
 */
public class VoloteaCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(VoloteaCollector.class);
    private static final String SCHEDULE_BASE = "https://json.volotea.com/dist/schedule";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int DAYS_AHEAD = 60;

    private final WebClient webClient;
    private final AirportResolver airportResolver;
    // Static files on a bucket rather than a booking engine, so this can be a little brisker
    // than the interval used against airlines' live fare APIs.
    private final RateLimiter rateLimiter = new RateLimiter(250);
    private final Map<String, List<FlightDto>> schedulesByRoute = new ConcurrentHashMap<>();

    public VoloteaCollector(WebClient webClient, AirportResolver airportResolver) {
        this.webClient = webClient;
        this.airportResolver = airportResolver;
    }

    @Override
    public Airline airline() {
        return Airline.VOLOTEA;
    }

    @Override
    public List<RouteDto> loadRoutes() {
        List<String> airports = new ArrayList<>(airportResolver.knownIataCodes());
        Collections.sort(airports);
        logger.info("Discovering Volotea routes across {} known airports...", airports.size());

        List<RouteDto> routes = new ArrayList<>();
        for (int i = 0; i < airports.size(); i++) {
            for (int j = i + 1; j < airports.size(); j++) {
                // Each unordered pair is asked about once: the file is published under one
                // ordering (alphabetical, in every case seen) and carries both directions.
                for (String direction : fetchSchedule(airports.get(i) + "-" + airports.get(j))) {
                    routes.add(new RouteDto(null, Airline.VOLOTEA,
                        direction.substring(0, 3), direction.substring(4, 7)));
                }
            }
        }

        logger.info("Loaded {} Volotea routes total", routes.size());
        return routes;
    }

    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        String key = route.fromAirport() + "-" + route.toAirport();
        List<FlightDto> cached = schedulesByRoute.get(key);
        if (cached != null) {
            return cached;
        }
        // Only reached if flights are collected without a preceding discovery pass in the same
        // run; the file may be published under either ordering, so try both.
        fetchSchedule(key);
        fetchSchedule(route.toAirport() + "-" + route.fromAirport());
        return schedulesByRoute.getOrDefault(key, List.of());
    }

    /**
     * Fetches one pair file and remembers every direction it contains.
     *
     * @return the direction keys found, e.g. {@code ["ATH-VIE", "VIE-ATH"]}; empty when Volotea
     *         doesn't fly the pair, which the bucket answers with a plain 404.
     */
    private List<String> fetchSchedule(String pair) {
        String url = SCHEDULE_BASE + "/" + pair + "_schedule.json";
        try {
            rateLimiter.acquire();
            String json = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            JsonNode root = mapper.readTree(json);
            List<String> directions = new ArrayList<>();
            Iterator<String> keys = root.fieldNames();
            while (keys.hasNext()) {
                String direction = keys.next();
                if (direction.length() != 7) {
                    continue;
                }
                List<FlightDto> flights = parseFlights(root.path(direction));
                if (!flights.isEmpty()) {
                    schedulesByRoute.put(direction, flights);
                    directions.add(direction);
                }
            }
            if (!directions.isEmpty()) {
                logger.info("Volotea flies {} ({} directions priced)", pair, directions.size());
            }
            return directions;
        } catch (Exception e) {
            logger.debug("No Volotea schedule for {}: {}", pair, e.getMessage());
            return List.of();
        }
    }

    private List<FlightDto> parseFlights(JsonNode schedule) {
        List<FlightDto> flights = new ArrayList<>();
        if (!schedule.isArray()) {
            return flights;
        }

        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(DAYS_AHEAD);
        for (JsonNode entry : schedule) {
            try {
                JsonNode prices = entry.path("Prices");
                if (!prices.isArray() || prices.isEmpty()) {
                    // A scheduled flight with no fare loaded - it exists, but nothing is on sale.
                    continue;
                }
                LocalDateTime departure = LocalDateTime.parse(entry.path("Departure").asText(), TIMESTAMP);
                LocalDateTime arrival = LocalDateTime.parse(entry.path("Arrival").asText(), TIMESTAMP);
                LocalDate day = departure.toLocalDate();
                if (day.isBefore(today) || day.isAfter(horizon)) {
                    continue;
                }

                // Several fare classes can be listed; the cheapest is the one worth comparing
                // against the other airlines, whose sources all quote their lowest available fare.
                JsonNode cheapest = null;
                for (JsonNode price : prices) {
                    if (cheapest == null || price.path("Price").asDouble() < cheapest.path("Price").asDouble()) {
                        cheapest = price;
                    }
                }

                String carrier = entry.path("CarrierCode").asText("");
                String number = entry.path("FlightNumber").asText("");
                String flightNumber = carrier.isBlank() ? number : carrier + number;

                flights.add(new FlightDto(
                    flightNumber.isBlank() ? "N/A" : flightNumber,
                    departure,
                    arrival,
                    cheapest.path("Price").asDouble(),
                    cheapest.path("Currency").asText("EUR")
                ));
            } catch (Exception e) {
                logger.warn("Failed to parse Volotea schedule entry: {}", e.getMessage());
            }
        }
        return flights;
    }

    /**
     * Spaces out calls to at most one per {@code minIntervalMillis}, shared across every
     * caller regardless of how many threads are calling concurrently - a simple leaky-bucket:
     * each acquire() reserves the next free slot and sleeps only as long as needed to reach it.
     */
    private static final class RateLimiter {
        private final long minIntervalMillis;
        private long nextAllowedTime = 0;

        RateLimiter(long minIntervalMillis) {
            this.minIntervalMillis = minIntervalMillis;
        }

        synchronized void acquire() {
            long now = System.currentTimeMillis();
            long waitUntil = Math.max(now, nextAllowedTime);
            nextAllowedTime = waitUntil + minIntervalMillis;
            long sleepMs = waitUntil - now;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
