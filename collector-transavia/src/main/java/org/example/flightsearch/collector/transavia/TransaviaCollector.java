package org.example.flightsearch.collector.transavia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.CollectionRefusedException;
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
     * Transavia does publish the airports it serves, at www.transavia.com/api/airports, and that
     * one request would replace every probe below. It is not used, because that path sits behind
     * Cloudflare and answers our client with a challenge - "Cf-Mitigated: challenge" - where the
     * fare calendar on the same host answers normally. Getting past a challenge means imitating a
     * browser closely enough to be mistaken for one, which is not something this project does.
     * So the network is worked out from the airports we already have metadata for.
     */
    /**
     * Transavia is a base carrier: essentially every route it flies has one end at a base, so
     * asking each base whether it serves each airport finds the network without probing every
     * ordered pair. These eight are the ones that answered with fares when asked - Groningen and
     * Bordeaux, also sometimes described as bases, did not, and are left out rather than costing
     * a fruitless 189 probes each.
     */
    private static final List<String> BASES =
        List.of("AMS", "RTM", "EIN", "ORY", "NTE", "LYS", "MPL", "BRU");

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
    // 1.2s still drew occasional refusals over a long scan; 1.5s ran a probe series clean.
    private final RateLimiter rateLimiter = new RateLimiter(1500);
    private final AtomicInteger consecutiveRefusals = new AtomicInteger();

    public TransaviaCollector(WebClient webClient, AirportResolver airportResolver) {
        this.webClient = webClient;
        this.airportResolver = airportResolver;
    }

    @Override
    public Airline airline() {
        return Airline.TRANSAVIA;
    }

    /**
     * One probe per base per airport, and that is the whole scan.
     *
     * <p>It used to be two phases: ask every known airport whether it connects to Amsterdam or
     * Orly, then ask every pair among the ones that did. That second phase was the expensive
     * part and almost entirely wasted - 3,800 probes to confirm what the base structure already
     * says, that Eindhoven does not fly to Faro via anywhere except a base. Asking each base
     * directly covers the same network in a quarter of the requests and covers it better: the
     * six bases beyond the two original seeds used to be reachable only through that sweep.
     */
    @Override
    public List<RouteDto> loadRoutes() {
        consecutiveRefusals.set(0);

        List<String> airports = new ArrayList<>(airportResolver.knownIataCodes());
        logger.info("Discovering the Transavia network across {} known airports, from {} bases",
            airports.size(), BASES);

        // A scheduled carrier flies a route in both directions, so one probe settles the pair.
        List<RouteDto> routes = new ArrayList<>();
        Set<String> pairs = new LinkedHashSet<>();
        for (String base : BASES) {
            int found = 0;
            for (String airport : airports) {
                if (airport.equals(base)) {
                    continue;
                }
                if (beingRefused()) {
                    throw new CollectionRefusedException(
                        Airline.TRANSAVIA, consecutiveRefusals.get(), "refused during route discovery");
                }
                if (!isServed(base, airport)) {
                    continue;
                }
                if (pairs.add(base + "-" + airport)) {
                    routes.add(new RouteDto(null, Airline.TRANSAVIA, base, airport));
                }
                if (pairs.add(airport + "-" + base)) {
                    routes.add(new RouteDto(null, Airline.TRANSAVIA, airport, base));
                }
                found++;
            }
            logger.info("Transavia serves {} destinations from {}", found, base);
        }

        logger.info("Loaded {} Transavia routes total", routes.size());
        return routes;
    }

    private boolean beingRefused() {
        return consecutiveRefusals.get() >= CONSECUTIVE_REFUSALS_BEFORE_ABANDONING;
    }

    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        logger.info("Loading Transavia fares for route {} -> {}", route.fromAirport(), route.toAirport());
        // One request covers the whole horizon - the endpoint takes a month range.
        List<FlightDto> flights = parseFares(requestFares(route.fromAirport(), route.toAirport()));
        if (beingRefused()) {
            throw new CollectionRefusedException(
                Airline.TRANSAVIA, consecutiveRefusals.get(), "refused while loading fares");
        }
        return flights;
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
