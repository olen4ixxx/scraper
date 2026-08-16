package org.example.flightsearch.collector.transavia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.RateLimiter;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transavia's fare calendar, the endpoint their own booking page calls. It needs no key and
 * no session, unlike their documented API - that one is now partner-only, with no
 * self-service registration left on the developer portal.
 *
 * <p>It answers a price per calendar day, like Ryanair's and unlike Vueling's "cheapest date
 * in this window", and a whole three-month span comes back in one request. What it does not
 * return is any time of day: a fare is a date and an amount, nothing more. Departure and
 * arrival are therefore stored as the placeholder end and start of that day, the same
 * convention the WizzAir rows already use, which keeps a date-only flight eligible as a
 * connection instead of silently dropping out of every window. It makes trip durations
 * meaningless for these rows, which is the accepted trade for not inventing times.
 *
 * <p>Prices are taken as EUR: the endpoint has no currency parameter, ignores one if given,
 * returns the same figures from every locale path, and belongs to a Dutch-French carrier
 * selling in euros - so there is one fixed currency and euros is what it is.
 */
public class TransaviaCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(TransaviaCollector.class);
    private static final String CALENDAR_FARES_API = "https://www.transavia.com/start/api/calendar-fares";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MONTHS_AHEAD = 3;

    /**
     * Seeds for route discovery, not a hardcoded network: Transavia flies out of bases, so
     * asking every airport we know about whether it connects to one of these finds the
     * network without probing all 20,000 ordered pairs. Both are confirmed live rather than
     * assumed - Amsterdam is the Dutch base, Paris Orly the French one.
     */
    private static final List<String> DISCOVERY_SEEDS = List.of("AMS", "ORY");

    /**
     * Discovery asks about thousands of pairs, and Transavia starts refusing when asked this
     * much this quickly - a few thousand probes at four per second took the refusal rate from
     * 0.4% to four in five. So it goes slower than the others to begin with, widens the gap
     * further when refused, and abandons the run rather than grinding through the remaining
     * thousands of probes once it is clear nothing is getting through.
     */
    private static final int CONSECUTIVE_REFUSALS_BEFORE_ABANDONING = 25;

    private final WebClient webClient;
    private final AirportResolver airportResolver;
    private final RateLimiter rateLimiter = new RateLimiter(1200);
    private final AtomicInteger consecutiveRefusals = new AtomicInteger();

    public TransaviaCollector(WebClient webClient, AirportResolver airportResolver) {
        this.webClient = webClient;
        this.airportResolver = airportResolver;
    }

    @Override
    public Airline airline() {
        return Airline.TRANSAVIA;
    }

    @Override
    public List<RouteDto> loadRoutes() {
        List<String> airports = new ArrayList<>(airportResolver.knownIataCodes());
        logger.info("Discovering Transavia network among {} known airports, seeded from {}...",
            airports.size(), DISCOVERY_SEEDS);

        consecutiveRefusals.set(0);

        // Anything Transavia serves at all reaches one of its bases, so a couple of probes per
        // airport is enough to tell whether it is worth looking at further.
        Set<String> inNetwork = new LinkedHashSet<>(DISCOVERY_SEEDS);
        for (String airport : airports) {
            if (beingRefused()) {
                return abandonDiscovery();
            }
            if (DISCOVERY_SEEDS.contains(airport)) {
                continue;
            }
            for (String seed : DISCOVERY_SEEDS) {
                if (isServed(airport, seed) || isServed(seed, airport)) {
                    inNetwork.add(airport);
                    break;
                }
            }
        }
        logger.info("Transavia serves {} of the airports we know about", inNetwork.size());

        List<RouteDto> routes = new ArrayList<>();
        for (String origin : inNetwork) {
            for (String destination : inNetwork) {
                if (beingRefused()) {
                    return abandonDiscovery();
                }
                if (!origin.equals(destination) && isServed(origin, destination)) {
                    routes.add(new RouteDto(null, Airline.TRANSAVIA, origin, destination));
                }
            }
        }

        logger.info("Loaded {} Transavia routes total", routes.size());
        return routes;
    }

    private boolean beingRefused() {
        return consecutiveRefusals.get() >= CONSECUTIVE_REFUSALS_BEFORE_ABANDONING;
    }

    /**
     * Returning nothing rather than a partial network on purpose: a half-scanned network saved
     * as if it were complete would be reused by every later run, quietly leaving out the routes
     * the scan never reached.
     */
    private List<RouteDto> abandonDiscovery() {
        logger.warn("Abandoning Transavia discovery - {} requests refused in a row, so they are "
            + "throttling us and the rest of the scan would only add to it. Nothing is saved from "
            + "a partial scan; try again later.", consecutiveRefusals.get());
        return List.of();
    }

    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        logger.info("Loading Transavia fares for route {} -> {}", route.fromAirport(), route.toAirport());
        // One request covers the whole horizon - the endpoint takes a month range.
        return parseFares(requestFares(route.fromAirport(), route.toAirport()));
    }

    private boolean isServed(String origin, String destination) {
        JsonNode response = requestFares(origin, destination);
        return response != null && response.isArray() && !response.isEmpty();
    }

    /**
     * A pair they don't fly answers 404 with {@code {"error":"Invalid route"}}, which is a real
     * answer. Anything else - the occasional 403, a timeout - is the request failing rather
     * than the route being absent, and treating those alike would quietly erase real routes
     * from the network. Those get one retry before being given up on.
     */
    private JsonNode requestFares(String origin, String destination) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode response = attemptFares(origin, destination);
                // A real answer, including "Invalid route" - either way they are talking to us.
                consecutiveRefusals.set(0);
                rateLimiter.recovered();
                return response;
            } catch (TransientFailure e) {
                // Widen the gap before trying again rather than immediately repeating the
                // request: retrying at once is more pressure at the moment they are asking
                // for less, which is how a 0.4% refusal rate became four in five.
                rateLimiter.backOff();
                if (attempt == 2) {
                    consecutiveRefusals.incrementAndGet();
                    logger.debug("Giving up on Transavia {} -> {}: {}", origin, destination, e.getMessage());
                }
            }
        }
        return null;
    }

    private JsonNode attemptFares(String origin, String destination) throws TransientFailure {
        YearMonth from = YearMonth.from(LocalDate.now());
        YearMonth to = from.plusMonths(MONTHS_AHEAD - 1L);
        String url = String.format("%s?dr=%s/%s&ac=1&cc=0&ic=0&ds=%s&as=%s&lf=Monetary",
            CALENDAR_FARES_API, from, to, origin, destination);

        try {
            rateLimiter.acquire();
            String json = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            return mapper.readTree(json);
        } catch (WebClientResponseException.NotFound e) {
            return null;
        } catch (Exception e) {
            throw new TransientFailure(e.getMessage());
        }
    }

    /** The request failed, which is not the same answer as "they don't fly this". */
    private static final class TransientFailure extends Exception {
        TransientFailure(String message) {
            super(message);
        }
    }

    private List<FlightDto> parseFares(JsonNode fares) {
        List<FlightDto> flights = new ArrayList<>();
        if (fares == null || !fares.isArray()) {
            // A route they don't fly answers {"error":"Invalid route"} rather than an empty list.
            return flights;
        }

        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(60);
        for (JsonNode fare : fares) {
            try {
                String dayText = fare.path("date").asText(null);
                JsonNode price = fare.path("price");
                if (dayText == null || price.isMissingNode() || price.isNull()) {
                    continue;
                }
                LocalDate day = LocalDate.parse(dayText);
                if (day.isBefore(today) || day.isAfter(horizon)) {
                    continue;
                }
                // See the class comment: a date-only fare, spread across its own day so that
                // it stays eligible as a connection.
                flights.add(new FlightDto("N/A", day.atTime(23, 59), day.atTime(0, 1),
                    price.asDouble(), "EUR"));
            } catch (Exception e) {
                logger.warn("Failed to parse Transavia fare entry: {}", e.getMessage());
            }
        }
        return flights;
    }

}
