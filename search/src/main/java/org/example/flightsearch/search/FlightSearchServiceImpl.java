package org.example.flightsearch.search;

import org.example.flightsearch.common.dto.PriceHistoryPoint;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.PolandAirports;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.db.entity.FlightWithPrice;
import org.example.flightsearch.db.entity.PriceSnapshotEntity;
import org.example.flightsearch.db.entity.RouteEntity;
import org.example.flightsearch.db.repository.AirportRepository;
import org.example.flightsearch.db.repository.FlightRepository;
import org.example.flightsearch.db.repository.PriceSnapshotRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FlightSearchServiceImpl implements FlightSearchService {
    private static final Logger logger = LoggerFactory.getLogger(FlightSearchServiceImpl.class);
    private static final String ANYWHERE = "ANYWHERE";
    private static final String WARSAW = "WARSAW";
    private static final String POLAND = "POLAND";
    private static final String COUNTRY_PREFIX = "COUNTRY:";
    private static final String CITY_PREFIX = "CITY:";
    private static final List<String> WARSAW_AIRPORTS = List.of("WAW", "WMI");

    @Value("${flight.search.min-connection-minutes:90}")
    private int minConnectionMinutes;

    @Value("${flight.search.max-connection-hours:12}")
    private int maxConnectionHours;

    private final FlightRepository flightRepository;
    private final RouteRepository routeRepository;
    private final AirportRepository airportRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;

    public FlightSearchServiceImpl(FlightRepository flightRepository, RouteRepository routeRepository,
                                    AirportRepository airportRepository, PriceSnapshotRepository priceSnapshotRepository) {
        this.flightRepository = flightRepository;
        this.routeRepository = routeRepository;
        this.airportRepository = airportRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    @Override
    public List<PriceHistoryPoint> getPriceHistory(Long flightId) {
        List<PriceHistoryPoint> history = new ArrayList<>();
        for (PriceSnapshotEntity snapshot : priceSnapshotRepository.findByFlightIdOrderByCollectedAt(flightId)) {
            history.add(new PriceHistoryPoint(snapshot.collectedAt(), snapshot.price(), snapshot.currency()));
        }
        return history;
    }

    /**
     * Route and airport lookups needed while building results, loaded once per search()
     * call instead of once per flight. With thousands of candidate flights (e.g. a
     * multi-week "from all of Poland" search), looking up each flight's route/airline/city
     * with its own DB round-trip was the dominant cost - route and airport tables are both
     * small enough (tens of thousands / hundreds of rows) to load in full up front instead.
     */
    private record SearchContext(Map<Long, RouteEntity> routesById, Map<String, AirportEntity> airportsByIata) {
        String fromAirport(Long routeId) {
            RouteEntity route = routesById.get(routeId);
            return route != null ? route.fromAirport() : "UNKNOWN";
        }

        String toAirport(Long routeId) {
            RouteEntity route = routesById.get(routeId);
            return route != null ? route.toAirport() : null;
        }

        String airline(Long routeId) {
            RouteEntity route = routesById.get(routeId);
            return route != null ? route.airline().name() : "UNKNOWN";
        }

        String city(String iata) {
            AirportEntity airport = airportsByIata.get(iata);
            return airport != null ? airport.city() : iata;
        }
    }

    private SearchContext buildContext() {
        Map<Long, RouteEntity> routesById = new HashMap<>();
        routeRepository.findAll().forEach(r -> routesById.put(r.id(), r));

        Map<String, AirportEntity> airportsByIata = new HashMap<>();
        airportRepository.findAll().forEach(a -> airportsByIata.put(a.iata(), a));

        return new SearchContext(routesById, airportsByIata);
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        long startTime = System.currentTimeMillis();
        logger.info("Searching flights: from={}, to={}, departure={}..{}, return={}..{}, directOnly={}, maxStops={}",
            request.from(), request.to(), request.departure(), request.departureRangeEnd(),
            request.returnDate(), request.returnRangeEnd(), request.directOnly(), request.maxStops());

        SearchContext ctx = buildContext();
        Set<String> fromAirports = resolveFromAirports(request.from());
        boolean anywhere = isAnywhere(request.to());
        List<LocalDate> departureDates = dateRange(request.departure(), request.departureRangeEnd());

        List<SearchResult> allResults;
        if (request.returnDate() != null) {
            allResults = searchRoundTrip(fromAirports, anywhere, departureDates, request, ctx);
        } else {
            allResults = searchOneWay(fromAirports, anywhere, departureDates, request, ctx);
        }

        sortResults(allResults, request.sortBy());

        // Limit results for Anywhere searches
        if (anywhere) {
            allResults = allResults.stream().limit(50).collect(Collectors.toList());
        }

        logger.info("Found {} flight paths in {}ms", allResults.size(), System.currentTimeMillis() - startTime);
        return allResults;
    }

    // Departure/arrival bounds for a whole date range at once - one query for the entire
    // range beats one query per day (a month-wide search used to mean ~30x the round trips
    // for no benefit, since the "departure between X and Y" queries already work over any span).
    private Instant rangeStart(List<LocalDate> dates) {
        return dates.get(0).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant rangeEnd(List<LocalDate> dates) {
        return dates.get(dates.size() - 1).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private List<SearchResult> searchOneWay(Set<String> fromAirports, boolean anywhere,
                                             List<LocalDate> departureDates, SearchRequest request, SearchContext ctx) {
        Set<String> toAirports = anywhere ? Set.of() : resolveToAirports(request.to());
        Instant start = rangeStart(departureDates);
        Instant end = rangeEnd(departureDates);
        List<SearchResult> results = new ArrayList<>();

        for (String from : fromAirports) {
            if (anywhere) {
                results.addAll(searchAnywhere(from, start, end, request, ctx));
            } else {
                for (String to : toAirports) {
                    results.addAll(searchSpecificRoute(from, to, start, end, request, ctx));
                }
            }
        }

        return results;
    }

    // Finds every outbound option across the whole departure range in one pass, groups them by
    // the actual (origin, destination) pair flown, then for each such pair looks for a return
    // leg across the whole return range and pairs every outbound option with every matching
    // return option whose date isn't before its own outbound date. Grouping by the pair actually
    // flown is what makes this work for "Anywhere" too - the return leg only ever gets searched
    // back from wherever the outbound leg actually landed.
    private List<SearchResult> searchRoundTrip(Set<String> fromAirports, boolean anywhere,
                                                List<LocalDate> departureDates, SearchRequest request, SearchContext ctx) {
        List<LocalDate> returnDates = dateRange(request.returnDate(), request.returnRangeEnd());
        Instant departureStart = rangeStart(departureDates);
        Instant departureEnd = rangeEnd(departureDates);
        Instant returnStart = rangeStart(returnDates);
        Instant returnEnd = rangeEnd(returnDates);
        Set<String> toAirports = anywhere ? Set.of() : resolveToAirports(request.to());

        List<SearchResult> outboundResults = new ArrayList<>();
        for (String from : fromAirports) {
            if (anywhere) {
                outboundResults.addAll(searchAnywhere(from, departureStart, departureEnd, request, ctx));
            } else {
                for (String to : toAirports) {
                    outboundResults.addAll(searchSpecificRoute(from, to, departureStart, departureEnd, request, ctx));
                }
            }
        }

        Map<List<String>, List<SearchResult>> byPair = outboundResults.stream()
            .collect(Collectors.groupingBy(this::originDestinationPair, LinkedHashMap::new, Collectors.toList()));

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<List<String>, List<SearchResult>> entry : byPair.entrySet()) {
            String origin = entry.getKey().get(0);
            String destination = entry.getKey().get(1);

            List<SearchResult> returnResults = searchSpecificRoute(destination, origin, returnStart, returnEnd, request, ctx);
            if (returnResults.isEmpty()) {
                continue;
            }
            for (SearchResult outbound : entry.getValue()) {
                LocalDate outboundDate = outbound.departure().toLocalDate();
                for (SearchResult ret : returnResults) {
                    if (ret.departure().toLocalDate().isBefore(outboundDate)) {
                        continue;
                    }
                    results.add(combineRoundTrip(outbound, ret));
                }
            }
        }

        return results;
    }

    private List<String> originDestinationPair(SearchResult result) {
        return List.of(
            result.segments().get(0).fromAirport(),
            result.segments().get(result.segments().size() - 1).toAirport()
        );
    }

    private SearchResult combineRoundTrip(SearchResult outbound, SearchResult ret) {
        List<String> airlines = new ArrayList<>(outbound.airlines());
        for (String airline : ret.airlines()) {
            if (!airlines.contains(airline)) {
                airlines.add(airline);
            }
        }
        return new SearchResult(
            outbound.totalPrice() + ret.totalPrice(),
            outbound.currency(),
            airlines,
            outbound.departure(),
            outbound.arrival(),
            outbound.duration(),
            outbound.numberOfStops(),
            outbound.segments(),
            ret.departure(),
            ret.arrival(),
            ret.duration(),
            ret.numberOfStops(),
            ret.segments()
        );
    }

    private List<LocalDate> dateRange(LocalDate start, LocalDate end) {
        List<LocalDate> dates = new ArrayList<>();
        dates.add(start);
        if (end != null) {
            LocalDate current = start.plusDays(1);
            while (!current.isAfter(end)) {
                dates.add(current);
                current = current.plusDays(1);
            }
        }
        return dates;
    }

    private Set<String> resolveFromAirports(String from) {
        Set<String> result = new HashSet<>();
        for (String rawToken : from.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (WARSAW.equalsIgnoreCase(token)) {
                result.addAll(WARSAW_AIRPORTS);
            } else if (POLAND.equalsIgnoreCase(token)) {
                result.addAll(PolandAirports.ALL);
            } else {
                result.add(token.toUpperCase());
            }
        }
        return result;
    }

    // "to" can carry several comma-separated tokens - individual airport codes, or
    // "COUNTRY:x" / "CITY:x" for whole-country / whole-city (multi-airport) selections -
    // built up client-side as the user adds chips to their search.
    private boolean isAnywhere(String to) {
        for (String token : to.split(",")) {
            if (ANYWHERE.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveToAirports(String to) {
        Set<String> result = new HashSet<>();
        for (String rawToken : to.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (WARSAW.equalsIgnoreCase(token)) {
                result.addAll(WARSAW_AIRPORTS);
            } else if (token.regionMatches(true, 0, COUNTRY_PREFIX, 0, COUNTRY_PREFIX.length())) {
                result.addAll(destinationsInCountry(token.substring(COUNTRY_PREFIX.length())));
            } else if (token.regionMatches(true, 0, CITY_PREFIX, 0, CITY_PREFIX.length())) {
                result.addAll(destinationsInCity(token.substring(CITY_PREFIX.length())));
            } else {
                result.add(token.toUpperCase());
            }
        }
        return result;
    }

    private List<AirportEntity> reachableDestinations() {
        List<AirportEntity> destinations = new ArrayList<>();
        airportRepository.findDestinationsFrom(PolandAirports.ALL).forEach(destinations::add);
        return destinations;
    }

    private Set<String> destinationsInCountry(String country) {
        return reachableDestinations().stream()
            .filter(a -> a.country().equalsIgnoreCase(country))
            .map(AirportEntity::iata)
            .collect(Collectors.toSet());
    }

    private Set<String> destinationsInCity(String city) {
        return reachableDestinations().stream()
            .filter(a -> a.city().equalsIgnoreCase(city))
            .map(AirportEntity::iata)
            .collect(Collectors.toSet());
    }

    private List<SearchResult> searchSpecificRoute(String from, String to, Instant start, Instant end,
                                                     SearchRequest request, SearchContext ctx) {
        List<SearchResult> results = new ArrayList<>();

        // Direct flights
        if (request.maxStops() >= 0) {
            List<FlightWithPrice> directFlights = flightRepository.findDirectFlights(from, to, start, end);
            directFlights = filterByAirlines(directFlights, request.airlines(), ctx);

            for (FlightWithPrice flight : directFlights) {
                results.add(createSearchResult(flight, ctx));
            }
        }

        // One-stop flights
        if (!request.directOnly() && request.maxStops() >= 1) {
            results.addAll(findOneStopFlights(from, to, start, end, request, ctx));
        }

        return results;
    }

    private List<SearchResult> searchAnywhere(String from, Instant start, Instant end,
                                                SearchRequest request, SearchContext ctx) {
        List<SearchResult> results = new ArrayList<>();

        // Direct flights to anywhere
        if (request.maxStops() >= 0) {
            List<FlightWithPrice> flights = flightRepository.findFlightsFromAnyDestination(from, start, end);
            flights = filterByAirlines(flights, request.airlines(), ctx);

            for (FlightWithPrice flight : flights) {
                results.add(createSearchResult(flight, ctx));
            }
        }

        // One-stop flights to anywhere
        if (!request.directOnly() && request.maxStops() >= 1) {
            results.addAll(findOneStopFlightsAnywhere(from, start, end, request, ctx));
        }

        return results;
    }

    // Fetches every first-leg flight once, then every candidate connecting flight (across all
    // connection airports at once) in a single second query, and matches them up in memory.
    // Previously this issued one connecting-flight query per first-leg flight - with a dense
    // route graph and a wide date range that meant hundreds to thousands of round trips for a
    // single search.
    private List<SearchResult> findOneStopFlights(String from, String to, Instant start, Instant end,
                                                    SearchRequest request, SearchContext ctx) {
        Duration minConnectionTime = Duration.ofMinutes(minConnectionMinutes);
        Duration maxConnectionTime = Duration.ofHours(maxConnectionHours);

        List<FlightWithPrice> fromFlights = flightRepository.findFlightsFrom(from, start, end);
        fromFlights = filterByAirlines(fromFlights, request.airlines(), ctx);

        Map<String, List<FlightWithPrice>> firstLegsByConnection = groupByConnectionAirport(fromFlights, to, ctx);
        if (firstLegsByConnection.isEmpty()) {
            return List.of();
        }

        Instant[] widestWindow = widestConnectionWindow(firstLegsByConnection.values(), minConnectionTime, maxConnectionTime);
        List<FlightWithPrice> candidateSecondLegs = flightRepository.findDirectFlightsFromAirports(
            firstLegsByConnection.keySet(), to, widestWindow[0], widestWindow[1]);
        candidateSecondLegs = filterByAirlines(candidateSecondLegs, request.airlines(), ctx);

        Map<String, List<FlightWithPrice>> secondLegsByOrigin = groupByFromAirport(candidateSecondLegs, ctx);

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, List<FlightWithPrice>> entry : firstLegsByConnection.entrySet()) {
            List<FlightWithPrice> secondLegs = secondLegsByOrigin.get(entry.getKey());
            if (secondLegs == null) {
                continue;
            }
            for (FlightWithPrice firstFlight : entry.getValue()) {
                Instant connectionFrom = firstFlight.arrival().plus(minConnectionTime);
                Instant connectionTo = firstFlight.arrival().plus(maxConnectionTime);
                for (FlightWithPrice secondFlight : secondLegs) {
                    if (isWithin(secondFlight.departure(), connectionFrom, connectionTo)) {
                        results.add(createSearchResult(firstFlight, secondFlight, ctx));
                    }
                }
            }
        }

        return results;
    }

    private List<SearchResult> findOneStopFlightsAnywhere(String from, Instant start, Instant end,
                                                             SearchRequest request, SearchContext ctx) {
        Duration minConnectionTime = Duration.ofMinutes(minConnectionMinutes);
        Duration maxConnectionTime = Duration.ofHours(maxConnectionHours);

        List<FlightWithPrice> fromFlights = flightRepository.findFlightsFromAnyDestination(from, start, end);
        fromFlights = filterByAirlines(fromFlights, request.airlines(), ctx);

        Map<String, List<FlightWithPrice>> firstLegsByConnection = groupByConnectionAirport(fromFlights, null, ctx);
        if (firstLegsByConnection.isEmpty()) {
            return List.of();
        }

        Instant[] widestWindow = widestConnectionWindow(firstLegsByConnection.values(), minConnectionTime, maxConnectionTime);
        List<FlightWithPrice> candidateSecondLegs = flightRepository.findFlightsFromAnyDestinationFromAirports(
            firstLegsByConnection.keySet(), widestWindow[0], widestWindow[1]);
        candidateSecondLegs = filterByAirlines(candidateSecondLegs, request.airlines(), ctx);

        Map<String, List<FlightWithPrice>> secondLegsByOrigin = groupByFromAirport(candidateSecondLegs, ctx);

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, List<FlightWithPrice>> entry : firstLegsByConnection.entrySet()) {
            String connectionAirport = entry.getKey();
            List<FlightWithPrice> secondLegs = secondLegsByOrigin.get(connectionAirport);
            if (secondLegs == null) {
                continue;
            }
            for (FlightWithPrice firstFlight : entry.getValue()) {
                Instant connectionFrom = firstFlight.arrival().plus(minConnectionTime);
                Instant connectionTo = firstFlight.arrival().plus(maxConnectionTime);
                for (FlightWithPrice secondFlight : secondLegs) {
                    if (!isWithin(secondFlight.departure(), connectionFrom, connectionTo)) {
                        continue;
                    }
                    String finalDestination = ctx.toAirport(secondFlight.routeId());
                    if (finalDestination != null && !finalDestination.equals(connectionAirport)) {
                        results.add(createSearchResult(firstFlight, secondFlight, ctx));
                    }
                }
            }
        }

        return results;
    }

    private boolean isWithin(Instant value, Instant from, Instant to) {
        return !value.isBefore(from) && !value.isAfter(to);
    }

    // Groups first-leg flights by the airport they land at (the potential connection point),
    // skipping any that land directly at the final destination (that's a direct flight, not a
    // connection) or whose route couldn't be resolved.
    private Map<String, List<FlightWithPrice>> groupByConnectionAirport(List<FlightWithPrice> flights, String excludeDestination, SearchContext ctx) {
        Map<String, List<FlightWithPrice>> byConnection = new HashMap<>();
        for (FlightWithPrice flight : flights) {
            String connectionAirport = ctx.toAirport(flight.routeId());
            if (connectionAirport == null || connectionAirport.equals(excludeDestination)) {
                continue;
            }
            byConnection.computeIfAbsent(connectionAirport, k -> new ArrayList<>()).add(flight);
        }
        return byConnection;
    }

    private Map<String, List<FlightWithPrice>> groupByFromAirport(List<FlightWithPrice> flights, SearchContext ctx) {
        Map<String, List<FlightWithPrice>> byOrigin = new HashMap<>();
        for (FlightWithPrice flight : flights) {
            byOrigin.computeIfAbsent(ctx.fromAirport(flight.routeId()), k -> new ArrayList<>()).add(flight);
        }
        return byOrigin;
    }

    // The union of every first-leg flight's individual connection window, so the batched
    // second-leg query can fetch everything relevant in one shot; exact per-flight matching
    // still happens afterwards in isWithin(), this is just the outer bound for the SQL query.
    private Instant[] widestConnectionWindow(java.util.Collection<List<FlightWithPrice>> groups,
                                              Duration minConnectionTime, Duration maxConnectionTime) {
        Instant widestFrom = null;
        Instant widestTo = null;
        for (List<FlightWithPrice> group : groups) {
            for (FlightWithPrice flight : group) {
                Instant cf = flight.arrival().plus(minConnectionTime);
                Instant ct = flight.arrival().plus(maxConnectionTime);
                if (widestFrom == null || cf.isBefore(widestFrom)) {
                    widestFrom = cf;
                }
                if (widestTo == null || ct.isAfter(widestTo)) {
                    widestTo = ct;
                }
            }
        }
        return new Instant[] { widestFrom, widestTo };
    }

    private List<FlightWithPrice> filterByAirlines(List<FlightWithPrice> flights, Set<Airline> airlines, SearchContext ctx) {
        if (airlines == null || airlines.isEmpty()) {
            return flights;
        }
        return flights.stream()
            .filter(f -> {
                String airlineName = ctx.airline(f.routeId());
                try {
                    Airline airline = Airline.valueOf(airlineName);
                    return airlines.contains(airline);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            })
            .collect(Collectors.toList());
    }

    private SearchResult createSearchResult(FlightWithPrice flight, SearchContext ctx) {
        String airline = ctx.airline(flight.routeId());
        String from = ctx.fromAirport(flight.routeId());
        String to = ctx.toAirport(flight.routeId());

        LocalDateTime departure = LocalDateTime.ofInstant(flight.departure(), ZoneOffset.UTC);
        LocalDateTime arrival = LocalDateTime.ofInstant(flight.arrival(), ZoneOffset.UTC);
        Duration duration = Duration.between(flight.departure(), flight.arrival());

        SearchResult.Segment segment = new SearchResult.Segment(
            flight.id(),
            airline,
            from,
            ctx.city(from),
            to,
            ctx.city(to),
            departure,
            arrival,
            flight.price(),
            flight.currency(),
            duration
        );

        return new SearchResult(
            flight.price(),
            flight.currency(),
            List.of(airline),
            departure,
            arrival,
            duration,
            0,
            List.of(segment),
            null,
            null,
            null,
            0,
            List.of()
        );
    }

    private SearchResult createSearchResult(FlightWithPrice firstFlight, FlightWithPrice secondFlight, SearchContext ctx) {
        String firstAirline = ctx.airline(firstFlight.routeId());
        String firstFrom = ctx.fromAirport(firstFlight.routeId());
        String firstTo = ctx.toAirport(firstFlight.routeId());

        String secondAirline = ctx.airline(secondFlight.routeId());
        String secondFrom = firstTo;
        String secondTo = ctx.toAirport(secondFlight.routeId());

        LocalDateTime departure = LocalDateTime.ofInstant(firstFlight.departure(), ZoneOffset.UTC);
        LocalDateTime arrival = LocalDateTime.ofInstant(secondFlight.arrival(), ZoneOffset.UTC);
        Duration duration = Duration.between(firstFlight.departure(), secondFlight.arrival());

        double totalPrice = firstFlight.price() + secondFlight.price();
        String currency = firstFlight.currency();

        SearchResult.Segment segment1 = new SearchResult.Segment(
            firstFlight.id(),
            firstAirline,
            firstFrom,
            ctx.city(firstFrom),
            firstTo,
            ctx.city(firstTo),
            LocalDateTime.ofInstant(firstFlight.departure(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(firstFlight.arrival(), ZoneOffset.UTC),
            firstFlight.price(),
            firstFlight.currency(),
            Duration.between(firstFlight.departure(), firstFlight.arrival())
        );

        SearchResult.Segment segment2 = new SearchResult.Segment(
            secondFlight.id(),
            secondAirline,
            secondFrom,
            ctx.city(secondFrom),
            secondTo,
            ctx.city(secondTo),
            LocalDateTime.ofInstant(secondFlight.departure(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(secondFlight.arrival(), ZoneOffset.UTC),
            secondFlight.price(),
            secondFlight.currency(),
            Duration.between(secondFlight.departure(), secondFlight.arrival())
        );

        return new SearchResult(
            totalPrice,
            currency,
            List.of(firstAirline, secondAirline),
            departure,
            arrival,
            duration,
            1,
            List.of(segment1, segment2),
            null,
            null,
            null,
            0,
            List.of()
        );
    }

    private void sortResults(List<SearchResult> results, SearchRequest.SortBy sortBy) {
        switch (sortBy) {
            case CHEAPEST -> results.sort(Comparator.comparingDouble(SearchResult::totalPrice));
            case SHORTEST -> results.sort(Comparator.comparing(SearchResult::duration));
            case EARLIEST_DEPARTURE -> results.sort(Comparator.comparing(SearchResult::departure));
            case LATEST_DEPARTURE -> results.sort(Comparator.comparing(SearchResult::departure).reversed());
            case FEWEST_STOPS -> results.sort(Comparator.comparingInt(SearchResult::numberOfStops)
                .thenComparingDouble(SearchResult::totalPrice));
        }
    }
}
