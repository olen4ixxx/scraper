package org.example.flightsearch.collector.vueling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.CollectionRefusedException;
import org.example.flightsearch.collector.RateLimiter;
import org.example.flightsearch.collector.Refusal;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vueling flies nothing to or from Poland - confirmed against their own API, which answers
 * "best prices not found" for every Polish airport in either direction. It is collected
 * anyway because this application also routes self-transfer connections: a Ryanair flight
 * from Poland into Barcelona or Rome, and a Vueling flight onward from there.
 *
 * <p>Their site calls one public endpoint, apiw.vueling.com/api/v1/bestPrices, which works
 * from a plain HTTP client with no key or session (unlike easyJet and Volotea, both of which
 * refuse any non-browser client outright). It answers a different question from Ryanair's
 * fare calendar though: not "what does each day cost" but "what is the cheapest date in this
 * window", one answer per destination. So a route yields one dated fare per month asked
 * about, not one per day.
 *
 * <p>It also returns no arrival time - only the departure. Rather than invent one from
 * distance and a guessed cruise speed, arrival is stored equal to departure, which shows as
 * a zero-length flight. Connection search still works (it needs the departure of the onward
 * leg), but total trip duration is wrong for any itinerary using a Vueling leg.
 *
 * <p>Unresolved: the departure time they give doesn't match their own published schedule.
 * Madrid to Tenerife comes back as an evening departure on a date whose site lists four
 * flights, none in the evening, and asking about several separate months returns evening
 * times throughout. The value is theirs, from the "departureDate" field, so it is not
 * fabricated here - but it has not been reconciled with what they publish, and until it is,
 * the time on a Vueling row is worth less than the date and price beside it.
 */
public class VuelingCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(VuelingCollector.class);
    private static final String BEST_PRICES_API = "https://apiw.vueling.com/api/v1/bestPrices";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MONTHS_AHEAD = 2;
    // How many destinations to ask about in one call. The site asks about a handful; this is
    // larger to keep route discovery to one request per origin, but not so large that the
    // query string becomes unreasonable.
    private static final int DESTINATION_BATCH = 40;

    private static final int CONSECUTIVE_REFUSALS_BEFORE_ABANDONING = 25;

    private final WebClient webClient;
    private final AirportResolver airportResolver;
    private final RateLimiter rateLimiter = new RateLimiter(400);
    private final AtomicInteger consecutiveRefusals = new AtomicInteger();

    public VuelingCollector(WebClient webClient, AirportResolver airportResolver) {
        this.webClient = webClient;
        this.airportResolver = airportResolver;
    }

    @Override
    public Airline airline() {
        return Airline.VUELING;
    }

    /**
     * Vueling publishes no route list, so routes are discovered by asking the fare endpoint
     * about every airport pair we know of and keeping the pairs it prices.
     */
    @Override
    public List<RouteDto> loadRoutes() {
        List<String> airports = new ArrayList<>(airportResolver.knownIataCodes());
        logger.info("Discovering Vueling routes across {} known airports...", airports.size());

        List<RouteDto> routes = new ArrayList<>();
        for (String origin : airports) {
            Set<String> destinations = new HashSet<>();
            for (int i = 0; i < airports.size(); i += DESTINATION_BATCH) {
                List<String> batch = airports.subList(i, Math.min(i + DESTINATION_BATCH, airports.size()));
                destinations.addAll(fetchPricedDestinations(origin, batch, LocalDate.now(), MONTHS_AHEAD));
            }
            destinations.remove(origin);
            for (String destination : destinations) {
                routes.add(new RouteDto(null, Airline.VUELING, origin, destination));
            }
            if (!destinations.isEmpty()) {
                logger.info("Found {} Vueling destinations from {}", destinations.size(), origin);
            }
        }

        logger.info("Loaded {} Vueling routes total", routes.size());
        return routes;
    }

    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        logger.info("Loading Vueling fares for route {} -> {}", route.fromAirport(), route.toAirport());

        // One call per month rather than one spanning call: the endpoint returns a single
        // cheapest date per window, so asking month by month is what produces more than one
        // dated fare for the route.
        List<FlightDto> flights = new ArrayList<>();
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i < MONTHS_AHEAD; i++) {
            flights.addAll(fetchFares(route.fromAirport(), List.of(route.toAirport()), month, 1));
            month = month.plusMonths(1);
        }
        return flights;
    }

    private Set<String> fetchPricedDestinations(String origin, List<String> destinations, LocalDate from, int months) {
        Set<String> priced = new HashSet<>();
        for (JsonNode fare : requestBestPrices(origin, destinations, from, months)) {
            String destination = fare.path("destinationCode").asText(null);
            if (destination != null && !destination.isBlank()) {
                priced.add(destination);
            }
        }
        return priced;
    }

    private List<FlightDto> fetchFares(String origin, List<String> destinations, LocalDate from, int months) {
        List<FlightDto> flights = new ArrayList<>();
        for (JsonNode fare : requestBestPrices(origin, destinations, from, months)) {
            try {
                String departureText = fare.path("departureDate").asText(null);
                if (departureText == null) {
                    continue;
                }
                LocalDateTime departure = LocalDateTime.parse(departureText);

                JsonNode integerPart = fare.path("integerPartPrice");
                if (integerPart.isMissingNode() || integerPart.isNull()) {
                    continue;
                }
                double price = integerPart.asDouble() + fare.path("decimalPartPrice").asDouble() / 100.0;

                // Flight number and arrival time are simply not in this response - "N/A" and a
                // repeated departure beat inventing either. See the class comment.
                flights.add(new FlightDto("N/A", departure, departure, price, "EUR"));
            } catch (Exception e) {
                logger.warn("Failed to parse Vueling fare entry: {}", e.getMessage());
            }
        }
        return flights;
    }

    private List<JsonNode> requestBestPrices(String origin, List<String> destinations, LocalDate from, int months) {
        String url = String.format("%s?originCode=%s&destinationCodes=%s&months=%d&startDate=%s&currencyCode=EUR",
            BEST_PRICES_API, origin, String.join(",", destinations), months, from);

        try {
            rateLimiter.acquire();
            String json = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            JsonNode fares = mapper.readTree(json).path("bestPrices").path("value");
            List<JsonNode> result = new ArrayList<>();
            if (fares.isArray()) {
                fares.forEach(result::add);
            }
            consecutiveRefusals.set(0);
            rateLimiter.recovered();
            return result;
        } catch (Exception e) {
            // A route Vueling doesn't fly answers 404 "best prices not found", so this is the
            // normal outcome for most pairs during discovery, not a failure worth shouting about.
            // Being refused is a different thing entirely, and treating the two alike is how a
            // scan quietly concludes an airline flies nowhere - see Refusal.
            if (Refusal.is(e)) {
                rateLimiter.backOff();
                int inARow = consecutiveRefusals.incrementAndGet();
                if (inARow >= CONSECUTIVE_REFUSALS_BEFORE_ABANDONING) {
                    throw new CollectionRefusedException(Airline.VUELING, inARow, e.getMessage());
                }
                logger.debug("Vueling refused {} -> {}: {}", origin, destinations, e.getMessage());
                return List.of();
            }
            consecutiveRefusals.set(0);
            logger.debug("No Vueling prices for {} -> {}: {}", origin, destinations, e.getMessage());
            return List.of();
        }
    }

}
