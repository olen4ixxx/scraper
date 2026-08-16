package org.example.flightsearch.collector.wizz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.currency.EurConverter;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * WizzAir fares over plain HTTP. Their booking site sits behind Kasada and cannot be scripted -
 * that is what the separate ticket-finder tool drives a real browser for - but the backend the
 * site calls, on a different host, is not protected at all: it answers an ordinary POST with no
 * key, no session and no browser. Nothing here works around the bot check; it never encounters
 * one.
 *
 * <p>Two endpoints do everything. The asset map returns their entire network - every city with
 * the places it flies to - so routes are read rather than discovered by probing thousands of
 * pairs. The fare chart then answers a price per day, a fortnight at a time.
 *
 * <p>Two things to know about the data. Fares carry a date and an amount but no time of day, so
 * departure and arrival are stored as the placeholder ends of the day, the convention the rest
 * of the WizzAir rows already use - it keeps a date-only flight eligible as a connection, at
 * the cost of a meaningless duration. And prices come in the departure market's currency
 * whatever you ask for (NOK from Stavanger, PLN from Warsaw), so each is converted to euros
 * before storage; one that cannot be converted is dropped rather than stored as a bare number
 * that would read as euros.
 */
public class WizzCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(WizzCollector.class);
    /**
     * The version is part of the path and is enforced - other values answer 404 - so it has to
     * be updated when WizzAir deploys a new one. A 404 from the map endpoint is the symptom,
     * and it is logged as such rather than passed off as an empty network.
     */
    private static final String API_BASE = "https://be.wizzair.com/29.12.0/Api";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DAYS_AHEAD = 60;
    // The largest interval the fare chart accepts; anything more is rejected outright.
    private static final int DAY_INTERVAL = 7;

    private final WebClient webClient;
    private final AirportResolver airportResolver;
    private final EurConverter eurConverter;
    private final RateLimiter rateLimiter = new RateLimiter(400);

    public WizzCollector(WebClient webClient, AirportResolver airportResolver, EurConverter eurConverter) {
        this.webClient = webClient;
        this.airportResolver = airportResolver;
        this.eurConverter = eurConverter;
    }

    @Override
    public Airline airline() {
        return Airline.WIZZAIR;
    }

    @Override
    public List<RouteDto> loadRoutes() {
        logger.info("Loading the WizzAir network map...");
        JsonNode map = fetchMap();
        if (map == null) {
            logger.error("Could not load the WizzAir map from {} - a 404 means their API version has "
                + "moved on and the path here needs updating; see the failure logged above for "
                + "anything else", API_BASE);
            return List.of();
        }

        Set<String> known = airportResolver.knownIataCodes();
        List<RouteDto> routes = new ArrayList<>();
        int skippedUnknown = 0;

        for (JsonNode city : map.path("cities")) {
            String origin = city.path("iata").asText(null);
            if (origin == null || !known.contains(origin)) {
                continue;
            }
            for (JsonNode connection : city.path("connections")) {
                if (!connection.path("isDirectFlight").asBoolean(false)) {
                    continue;
                }
                String destination = connection.path("iata").asText(null);
                if (destination == null) {
                    continue;
                }
                // Airports outside the reference dataset have no metadata to save them under,
                // so collecting their fares would only produce rows that get skipped later.
                if (!known.contains(destination)) {
                    skippedUnknown++;
                    continue;
                }
                routes.add(new RouteDto(null, Airline.WIZZAIR, origin, destination));
            }
        }

        logger.info("Loaded {} WizzAir routes ({} skipped for airports outside the reference dataset)",
            routes.size(), skippedUnknown);
        return routes;
    }

    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        logger.info("Loading WizzAir fares for route {} -> {}", route.fromAirport(), route.toAirport());

        List<FlightDto> flights = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(DAYS_AHEAD);
        // Each call answers for the days either side of the one asked about, so the window
        // advances by twice the interval.
        for (LocalDate centre = today.plusDays(DAY_INTERVAL); centre.isBefore(horizon);
                centre = centre.plusDays(2L * DAY_INTERVAL)) {
            flights.addAll(parseFares(fetchFareChart(route, centre), today, horizon));
        }
        return flights;
    }

    private JsonNode fetchMap() {
        try {
            rateLimiter.acquire();
            String json = webClient.get()
                .uri(API_BASE + "/asset/map")
                .retrieve()
                .bodyToMono(String.class)
                .block();
            return mapper.readTree(json);
        } catch (Exception e) {
            logger.error("Failed to load the WizzAir map: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode fetchFareChart(RouteDto route, LocalDate centre) {
        String body = String.format("""
            {"isRescueFare":false,"adultCount":1,"childCount":0,"infantCount":0,\
            "flightList":[{"departureStation":"%s","arrivalStation":"%s","date":"%s"}],\
            "dayInterval":%d,"priceType":"regular"}""",
            route.fromAirport(), route.toAirport(), centre, DAY_INTERVAL);

        try {
            rateLimiter.acquire();
            String json = webClient.post()
                .uri(API_BASE + "/asset/farechart")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            return mapper.readTree(json);
        } catch (Exception e) {
            logger.debug("No WizzAir fares for {} -> {} around {}: {}",
                route.fromAirport(), route.toAirport(), centre, e.getMessage());
            return null;
        }
    }

    private List<FlightDto> parseFares(JsonNode response, LocalDate today, LocalDate horizon) {
        List<FlightDto> flights = new ArrayList<>();
        if (response == null) {
            return flights;
        }

        for (JsonNode fare : response.path("outboundFlights")) {
            try {
                String dayText = fare.path("date").asText(null);
                JsonNode price = fare.path("price");
                if (dayText == null || price.isMissingNode() || price.isNull()) {
                    continue;
                }
                LocalDate day = LocalDate.parse(dayText.substring(0, 10));
                if (day.isBefore(today) || day.isAfter(horizon)) {
                    continue;
                }

                String currency = price.path("currencyCode").asText(null);
                Optional<Double> euros = eurConverter.toEur(price.path("amount").asDouble(), currency);
                if (euros.isEmpty()) {
                    logger.warn("Dropping a WizzAir fare priced in {} - no exchange rate for it", currency);
                    continue;
                }

                // The fare chart gives no flight number and no time of day - see the class comment.
                flights.add(new FlightDto("N/A", day.atTime(23, 59), day.atTime(0, 1), euros.get(), "EUR"));
            } catch (Exception e) {
                logger.warn("Failed to parse a WizzAir fare entry: {}", e.getMessage());
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
