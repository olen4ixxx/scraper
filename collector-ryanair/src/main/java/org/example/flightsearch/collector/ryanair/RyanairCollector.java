package org.example.flightsearch.collector.ryanair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.RateLimiter;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.PolandAirports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RyanairCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(RyanairCollector.class);
    private static final String API_BASE = "https://services-api.ryanair.com";
    private static final String ROUTES_API_BASE = "https://www.ryanair.com/api/views/locate/searchWidget/routes/en/airport";
    private static final ObjectMapper mapper = new ObjectMapper();

    // Excluded from full hub-network collection - non-Schengen, different visa regime for a
    // Polish/EU traveller self-transferring through here, out of scope for now.
    private static final Set<String> NON_SCHENGEN_AIRPORTS = Set.of(
        // United Kingdom
        "ABZ", "BFS", "BHX", "BOH", "BRS", "EDI", "EMA", "GLA", "LBA", "LGW", "LPL", "LTN", "MAN", "NCL", "STN",
        // Ireland
        "DUB", "SNN",
        // Cyprus
        "LCA", "PFO",
        // Balkans (non-Schengen)
        "TIA", "SJJ", "TGD", "TIV", "OHD", "SKP", "BEG", "PRN",
        // Eastern Europe (non-Schengen)
        "KIV", "RMO", "IEV", "KBP", "ODS", "KUT",
        // Middle East / North Africa
        "TLV", "AMM", "DOH", "AUH", "DXB", "HRG", "RMF", "SSH", "AGA", "RAK", "RBA"
    );

    // A large hub-network crawl fired thousands of unpaced requests and got 403'd by Ryanair
    // within a couple of minutes (confirmed live). This gates every outbound call - shared
    // across however many routes CollectionService is processing concurrently - so total
    // request rate stays capped regardless of concurrency upstream, instead of every worker
    // thread hammering Ryanair in parallel with no pacing at all.
    private final RateLimiter rateLimiter = new RateLimiter(400);

    private final WebClient webClient;

    public RyanairCollector(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Airline airline() {
        return Airline.RYANAIR;
    }

    @Override
    public List<RouteDto> loadRoutes() {
        logger.info("Loading Ryanair routes from all Polish airports...");
        List<RouteDto> routes = new ArrayList<>();

        for (String origin : PolandAirports.ALL) {
            routes.addAll(fetchRoutesFrom(origin));
        }
        logger.info("Loaded {} outbound Ryanair routes from Poland", routes.size());

        // Round-trip search needs a flight departing FROM the destination back to Poland, and
        // genuine one-stop connections need the destination's full onward network (not just
        // its route back to Poland) - so for every destination we just found, fetch its
        // complete route list too. Non-Schengen destinations are skipped (see NON_SCHENGEN_AIRPORTS).
        Set<String> destinations = new HashSet<>();
        for (RouteDto route : routes) {
            destinations.add(route.toAirport());
        }
        destinations.removeAll(PolandAirports.ALL);
        destinations.removeAll(NON_SCHENGEN_AIRPORTS);

        List<RouteDto> hubRoutes = new ArrayList<>();
        for (String destination : destinations) {
            hubRoutes.addAll(fetchRoutesFrom(destination));
        }
        logger.info("Loaded {} hub routes from {} Schengen destinations", hubRoutes.size(), destinations.size());
        routes.addAll(hubRoutes);

        logger.info("Loaded {} Ryanair routes total", routes.size());
        return routes;
    }

    private List<RouteDto> fetchRoutesFrom(String origin) {
        String url = ROUTES_API_BASE + "/" + origin;
        try {
            rateLimiter.acquire();
            String json = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            JsonNode root = mapper.readTree(json);
            List<RouteDto> routes = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    String destination = entry.path("arrivalAirport").path("code").asText(null);
                    if (destination != null && !destination.isBlank()) {
                        routes.add(new RouteDto(null, Airline.RYANAIR, origin, destination));
                    }
                }
            }
            logger.info("Found {} Ryanair destinations from {}", routes.size(), origin);
            return routes;
        } catch (Exception e) {
            logger.error("Failed to load Ryanair routes from {}", origin, e);
            return List.of();
        }
    }
    
    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        logger.info("Loading Ryanair flights for route {} -> {}", route.fromAirport(), route.toAirport());

        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(60);

        List<FlightDto> flights = new ArrayList<>();
        YearMonth month = YearMonth.from(today);
        YearMonth endMonth = YearMonth.from(end);
        while (!month.isAfter(endMonth)) {
            flights.addAll(fetchMonth(route, month, today, end));
            month = month.plusMonths(1);
        }
        return flights;
    }

    private List<FlightDto> fetchMonth(RouteDto route, YearMonth month, LocalDate today, LocalDate end) {
        // "cheapestPerDay" is Ryanair's fare-calendar API (powers their own date-picker UI):
        // unlike oneWayFares it returns one real price per calendar day, not just the single
        // cheapest fare across the whole search window.
        // Ryanair otherwise prices each route in the departure airport's home currency (PLN
        // from Poland, EUR from Italy, etc). Since round-trip search sums an outbound and a
        // return leg that can depart from different countries, we force one common currency
        // (EUR) for every request - "currency=EUR" is respected regardless of route/market.
        String url = String.format("%s/farfnd/v4/oneWayFares/%s/%s/cheapestPerDay?outboundMonthOfDate=%s-01&currency=EUR",
            API_BASE, route.fromAirport(), route.toAirport(), month);

        try {
            rateLimiter.acquire();
            String json = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            JsonNode root = mapper.readTree(json);
            return parseCalendarFares(root, today, end);
        } catch (Exception e) {
            logger.warn("Failed to load Ryanair fare calendar for {} -> {} ({}): {}",
                route.fromAirport(), route.toAirport(), month, e.getMessage());
            return List.of();
        }
    }

    private List<FlightDto> parseCalendarFares(JsonNode root, LocalDate today, LocalDate end) {
        List<FlightDto> flights = new ArrayList<>();

        JsonNode fares = root.path("outbound").path("fares");
        if (fares.isArray()) {
            for (JsonNode fareNode : fares) {
                try {
                    if (fareNode.path("unavailable").asBoolean(true) || fareNode.path("soldOut").asBoolean(false)) {
                        continue;
                    }

                    String dayStr = fareNode.path("day").asText(null);
                    if (dayStr == null) {
                        continue;
                    }
                    LocalDate day = LocalDate.parse(dayStr);
                    if (day.isBefore(today) || day.isAfter(end)) {
                        continue;
                    }

                    JsonNode priceNode = fareNode.path("price");
                    if (priceNode.isMissingNode() || priceNode.isNull()) {
                        continue;
                    }
                    double price = priceNode.path("value").asDouble();
                    String currency = priceNode.path("currencyCode").asText();

                    String departureDateTime = fareNode.path("departureDate").asText(null);
                    String arrivalDateTime = fareNode.path("arrivalDate").asText(null);
                    if (departureDateTime == null || arrivalDateTime == null) {
                        continue;
                    }

                    DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                    LocalDateTime departure = LocalDateTime.parse(departureDateTime, formatter);
                    LocalDateTime arrival = LocalDateTime.parse(arrivalDateTime, formatter);

                    // The fare-calendar endpoint doesn't expose an operational flight number
                    // (unlike oneWayFares) - "N/A" avoids inventing one that looks real.
                    flights.add(new FlightDto("N/A", departure, arrival, price, currency));
                } catch (Exception e) {
                    logger.warn("Failed to parse calendar fare entry: {}", e.getMessage());
                }
            }
        }

        return flights;
    }

}
