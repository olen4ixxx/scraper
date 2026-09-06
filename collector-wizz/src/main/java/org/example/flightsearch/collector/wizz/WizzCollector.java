package org.example.flightsearch.collector.wizz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.ApiMovedException;
import org.example.flightsearch.collector.ApiVersionStore;
import org.example.flightsearch.collector.CollectionRefusedException;
import org.example.flightsearch.collector.RateLimiter;
import org.example.flightsearch.collector.Refusal;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.currency.EurConverter;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Unprotected is not the same as unlimited, though. The fare chart starts answering 503 when
 * asked too often - 2.5 requests a second had it refusing 28 seconds into a run and refusing 421
 * of the next 429 - and the refusal is per-address, not per-client: once it starts, a browser
 * asking by hand from the same address is turned away too. So the pace here is deliberate, it
 * widens further when they push back, and a run that keeps being refused stops instead of walking
 * the rest of the network collecting nothing.
 *
 * <p>Two things to know about the data. Fares carry a date and an amount but no time of day, so
 * departure and arrival are stored as the placeholder ends of the day, the convention the rest
 * of the WizzAir rows already use - it keeps a date-only flight eligible as a connection, at
 * the cost of a meaningless duration. And prices come in the departure market's currency
 * whatever you ask for (NOK from Stavanger, PLN from Warsaw), and are stored that way: the
 * conversion to euros happens when a search displays them. Converting before storage froze each
 * fare at the rate of the day it was collected, which aged badly on its own and became worse
 * once unchanged fares stopped being rewritten - and it made the price history unreadable,
 * because a move in the euro looked exactly like a move in the fare. A currency with no rate at
 * all is still dropped at collection rather than stored as a number nothing can display.
 */
public class WizzCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(WizzCollector.class);
    /**
     * The version is part of the path and is enforced - other values answer 404 - and WizzAir
     * moves it every few weeks. A 404 is therefore read as "the path is gone", not as "this route
     * has no flights", and the run goes looking for where it went rather than asking to be edited.
     */
    private static final String API_BASE_TEMPLATE = "https://be.wizzair.com/%s/Api";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DAYS_AHEAD = 60;
    // The largest interval the fare chart accepts; anything more is rejected outright.
    private static final int DAY_INTERVAL = 7;

    /**
     * How many refusals in a row mean the run is over rather than unlucky. Being turned away is
     * not rare enough to treat as fatal on its own - the occasional 503 comes back on the next
     * request - but a couple of dozen without a single answer between them is the site saying
     * stop, and the remaining thousands of requests would neither collect anything nor be a
     * decent thing to send.
     */
    private static final int CONSECUTIVE_REFUSALS_BEFORE_ABANDONING = 25;

    /**
     * How far ahead to look when the version moves, and the shape of the search.
     *
     * <p>There is no published list of versions and nothing that names the current one - their
     * homepage carries it, but answers 405 to anything that is not a browser - so the only way to
     * find the new one is to ask for versions until one answers. The moves seen so far, 29.12.0 to
     * 29.14.0 to 29.15.1 inside a fortnight, were a patch and two minors, so the grid leans that
     * way: the next four patches, then the next three minors with their first four patches, then
     * the first three of the next major. Nineteen requests, spaced by the same two seconds as
     * everything else, so a move costs about forty seconds once and nothing afterwards.
     */
    private static final int PATCHES_AHEAD = 4;
    private static final int MINORS_AHEAD = 3;
    private static final int PATCHES_PER_MINOR = 4;
    private static final int MAJORS_AHEAD = 3;

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private final WebClient webClient;
    private final AirportResolver airportResolver;
    private final EurConverter eurConverter;
    private final ApiVersionStore versions;
    private final String configuredVersion;
    // Moves when they move. Guarded by versionLock, read on every request.
    private volatile String version;
    private final Object versionLock = new Object();
    /**
     * Two seconds, measured rather than guessed. A steady series at one second was allowed 45
     * requests and refused from the 46th; the same series at two seconds ran 40 for 40 with no
     * refusal at all. Their limit sits near fifty requests a minute, and the run this code used
     * to make - 2.5 a second - was over it by a factor of three within half a minute of starting.
     *
     * <p>It makes a full pass over the network expensive: four requests a route, some 4,400 in
     * all, is around two and a half hours. That is the price of what they allow, and it is why
     * routes are collected longest-unvisited first, so a pass that doesn't finish still moves
     * coverage forward instead of refreshing the same head of the list.
     */
    private final RateLimiter rateLimiter = new RateLimiter(2000);
    private final AtomicInteger consecutiveRefusals = new AtomicInteger();

    public WizzCollector(WebClient webClient, AirportResolver airportResolver, EurConverter eurConverter,
                         String apiVersion, ApiVersionStore versions) {
        this.webClient = webClient;
        this.airportResolver = airportResolver;
        this.eurConverter = eurConverter;
        this.configuredVersion = apiVersion;
        this.versions = versions;
    }

    /**
     * The version in use: whatever a previous run last found to work, or the configured one if
     * none ever has. Read once and then held, so a pass does not ask the database per request.
     */
    private String version() {
        String known = version;
        if (known != null) {
            return known;
        }
        synchronized (versionLock) {
            if (version == null) {
                version = versions.current(Airline.WIZZAIR.name())
                    .filter(v -> !v.isBlank())
                    .orElse(configuredVersion);
            }
            return version;
        }
    }

    private String apiBase() {
        return String.format(API_BASE_TEMPLATE, version());
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
            logger.error("Could not load the WizzAir map from {} - see the failure logged above. "
                + "A moved API version is followed automatically, so this is something else",
                apiBase());
            return List.of();
        }

        Set<String> known = airportResolver.knownIataCodes();
        Set<String> metropolitanAreas = metropolitanAreas(map);
        List<RouteDto> routes = new ArrayList<>();
        int skippedUnknown = 0;

        for (JsonNode city : map.path("cities")) {
            String origin = city.path("iata").asText(null);
            if (origin == null || !known.contains(origin) || metropolitanAreas.contains(origin)) {
                continue;
            }
            for (JsonNode connection : city.path("connections")) {
                if (!connection.path("isDirectFlight").asBoolean(false)) {
                    continue;
                }
                String destination = connection.path("iata").asText(null);
                if (destination == null || metropolitanAreas.contains(destination)) {
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

    /**
     * The codes standing for a whole city rather than an airport - ROM for Rome, LON for London,
     * WSW for Warsaw. The map lists them among the cities and connects them like anything else,
     * so taken at face value they add a second copy of every route already covered by the real
     * airports underneath them: 393 routes that are Fiumicino and Ciampino counted again as
     * "Rome". They give themselves away by appearing as the "mac" of the airports they group.
     */
    private static Set<String> metropolitanAreas(JsonNode map) {
        Set<String> macs = new HashSet<>();
        for (JsonNode city : map.path("cities")) {
            String mac = city.path("mac").asText(null);
            if (mac != null && !mac.isBlank()) {
                macs.add(mac);
            }
        }
        return macs;
    }

    private JsonNode fetchMap() {
        try {
            return requestMap(version());
        } catch (Exception e) {
            if (isNotFound(e)) {
                // Not an empty network - a path that no longer exists. Find where it went and ask
                // again; if nothing answers, rediscover throws and the pass stops loudly.
                rediscover(version());
                try {
                    return requestMap(version());
                } catch (Exception again) {
                    logger.error("Failed to load the WizzAir map even on {}: {}",
                        version(), again.getMessage());
                    return null;
                }
            }
            logger.error("Failed to load the WizzAir map: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode requestMap(String version) throws Exception {
        rateLimiter.acquire();
        String json = webClient.get()
            .uri(String.format(API_BASE_TEMPLATE, version) + "/asset/map")
            .retrieve()
            .bodyToMono(String.class)
            .block();
        return mapper.readTree(json);
    }

    private JsonNode fetchFareChart(RouteDto route, LocalDate centre) {
        String body = String.format("""
            {"isRescueFare":false,"adultCount":1,"childCount":0,"infantCount":0,\
            "flightList":[{"departureStation":"%s","arrivalStation":"%s","date":"%s"}],\
            "dayInterval":%d,"priceType":"regular"}""",
            route.fromAirport(), route.toAirport(), centre, DAY_INTERVAL);

        try {
            return answered(postFareChart(version(), body));
        } catch (Exception e) {
            if (isNotFound(e)) {
                rediscover(version());
                try {
                    return answered(postFareChart(version(), body));
                } catch (Exception again) {
                    logger.debug("No WizzAir fares for {} -> {} around {} on {}: {}",
                        route.fromAirport(), route.toAirport(), centre, version(), again.getMessage());
                    return null;
                }
            }
            if (Refusal.is(e)) {
                rateLimiter.backOff();
                int inARow = consecutiveRefusals.incrementAndGet();
                if (inARow >= CONSECUTIVE_REFUSALS_BEFORE_ABANDONING) {
                    throw new CollectionRefusedException(Airline.WIZZAIR, inARow, e.getMessage());
                }
                logger.debug("WizzAir refused {} -> {} around {}: {}",
                    route.fromAirport(), route.toAirport(), centre, e.getMessage());
                return null;
            }
            consecutiveRefusals.set(0);
            logger.debug("No WizzAir fares for {} -> {} around {}: {}",
                route.fromAirport(), route.toAirport(), centre, e.getMessage());
            return null;
        }
    }

    private JsonNode postFareChart(String version, String body) throws Exception {
        rateLimiter.acquire();
        String json = webClient.post()
            .uri(String.format(API_BASE_TEMPLATE, version) + "/asset/farechart")
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .block();
        return mapper.readTree(json);
    }

    /** Takes an answer as proof the pace is sustainable and the run is not being turned away. */
    private JsonNode answered(JsonNode chart) {
        consecutiveRefusals.set(0);
        rateLimiter.recovered();
        return chart;
    }

    /**
     * Finds the version WizzAir has moved to, adopts it, and writes it down for later runs.
     *
     * <p>This used to be a line in the log asking someone to edit a property, which meant every
     * move cost a pass and a deploy - and the first one cost nine days, because until a 404 was
     * told apart from an empty route nobody knew there was anything to edit. The run that notices
     * is the run best placed to fix it: it is already talking to them, and it can prove a candidate
     * by asking for the map on it.
     *
     * <p>Held under the version lock, so several routes hitting the 404 at the same moment take
     * turns rather than each running its own search: the ones that arrive second find the version
     * already changed and go straight back to collecting. The lock is not on the reading path -
     * {@link #version()} takes it once, before the first request - so this blocks nothing that
     * could have succeeded anyway.
     */
    private String rediscover(String stale) {
        synchronized (versionLock) {
            if (!stale.equals(version)) {
                return version;
            }
            Matcher parts = VERSION.matcher(stale);
            if (!parts.matches()) {
                throw new ApiMovedException(Airline.WIZZAIR, apiBase() + "/asset/farechart");
            }
            int major = Integer.parseInt(parts.group(1));
            int minor = Integer.parseInt(parts.group(2));
            int patch = Integer.parseInt(parts.group(3));

            logger.warn("WizzAir's API is no longer at {}; looking for where it moved to", stale);
            for (String candidate : candidates(major, minor, patch)) {
                if (!serves(candidate)) {
                    continue;
                }
                logger.warn("WizzAir moved their API from {} to {}; carrying on there, and later "
                    + "runs will start from it", stale, candidate);
                version = candidate;
                try {
                    versions.remember(Airline.WIZZAIR.name(), candidate);
                } catch (Exception e) {
                    // Worth finishing this pass on the new version even if the note does not stick;
                    // the next run then pays for the search again, which is forty seconds.
                    logger.warn("Could not write down WizzAir's new API version {}, so the next run "
                        + "will have to find it again: {}", candidate, e.getMessage());
                }
                return candidate;
            }
            throw new ApiMovedException(Airline.WIZZAIR, apiBase() + "/asset/farechart");
        }
    }

    /**
     * The versions to try, nearest first, so the usual small step is found in a request or two.
     *
     * <p>Package-private and pure, because the shape of the search is the part worth pinning down
     * in a test: a grid that skipped the version actually in use would look exactly like WizzAir
     * having disappeared.
     */
    static List<String> candidates(int major, int minor, int patch) {
        List<String> tries = new ArrayList<>();
        for (int p = 1; p <= PATCHES_AHEAD; p++) {
            tries.add(major + "." + minor + "." + (patch + p));
        }
        for (int m = 1; m <= MINORS_AHEAD; m++) {
            for (int p = 0; p < PATCHES_PER_MINOR; p++) {
                tries.add(major + "." + (minor + m) + "." + p);
            }
        }
        for (int p = 0; p < MAJORS_AHEAD; p++) {
            tries.add((major + 1) + ".0." + p);
        }
        return tries;
    }

    /**
     * Whether this is a version they serve, asked with the cheapest question there is.
     *
     * <p>The map answers 200 on a live version and 404 on anything else, and the answer need not
     * even be read. A refusal is neither: it says nothing about the version, so it is retried once
     * rather than counted as a no - reading a 503 as "not this one" is how a search walks straight
     * past the version it was looking for.
     */
    private boolean serves(String candidate) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                rateLimiter.acquire();
                webClient.get()
                    .uri(String.format(API_BASE_TEMPLATE, candidate) + "/asset/map")
                    .retrieve()
                    .toBodilessEntity()
                    .block();
                rateLimiter.recovered();
                return true;
            } catch (Exception e) {
                if (Refusal.is(e)) {
                    rateLimiter.backOff();
                    continue;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * A 404 here is not a route without flights - a pair they don't fly answers 400 with
     * "InvalidArrivalStationCode". It means the path itself is gone, which for an API carrying
     * its version in the path means the version moved.
     */
    private static boolean isNotFound(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException http) {
                return http.getStatusCode().value() == 404;
            }
        }
        return false;
    }

    // Package-private so a test can hold it: this is where 13,167 fares of nothing once got in.
    List<FlightDto> parseFares(JsonNode response, LocalDate today, LocalDate horizon) {
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
                // Days without a flight come back as entries too, marked "noData" and carrying
                // an amount of zero. Taken at face value they read as free flights, which is
                // how 1,692 fares of nothing reached the database on the first run.
                if (!"price".equals(fare.path("priceType").asText()) || price.path("amount").asDouble() <= 0) {
                    continue;
                }
                LocalDate day = LocalDate.parse(dayText.substring(0, 10));
                if (day.isBefore(today) || day.isAfter(horizon)) {
                    continue;
                }

                String currency = price.path("currencyCode").asText(null);
                double amount = price.path("amount").asDouble();
                // Kept in the currency WizzAir quoted, and converted when a search displays it.
                // Converting here instead froze each fare at the exchange rate of the day it was
                // collected: a price stored three weeks ago was three weeks out of date in a way
                // that had nothing to do with the fare, and got worse once unchanged fares
                // stopped being rewritten. It also made the price history unreadable, because a
                // move in the euro was indistinguishable from a move in the fare.
                //
                // Still checked here rather than only at display time: a currency with no rate
                // can never be shown, and finding that out at collection is where it can be said
                // out loud.
                if (eurConverter.toEur(amount, currency).isEmpty()) {
                    logger.warn("Dropping a WizzAir fare priced in {} - no exchange rate for it", currency);
                    continue;
                }

                // The fare chart gives no flight number and no time of day - see the class comment.
                flights.add(new FlightDto("N/A", day.atTime(23, 59), day.atTime(0, 1), amount, currency, false));
            } catch (Exception e) {
                logger.warn("Failed to parse a WizzAir fare entry: {}", e.getMessage());
            }
        }
        return flights;
    }

}
