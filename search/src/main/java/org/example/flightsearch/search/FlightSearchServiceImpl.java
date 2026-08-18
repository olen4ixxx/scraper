package org.example.flightsearch.search;

import org.example.flightsearch.common.dto.PriceHistoryPoint;
import org.example.flightsearch.common.currency.EurConverter;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.PolandAirports;
import org.example.flightsearch.common.model.Schengen;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    // Enough to browse through and to sort meaningfully, small enough that the page stays a
    // couple of MB. Uncapped, a wide round-trip search rendered 3.5M itineraries into 18GB of
    // HTML - the search itself took 5s, the browser never finished.
    /** Below this, in euros, a "fare" is a sentinel or a promo teaser rather than a ticket. */
    private static final double CHEAPEST_PLAUSIBLE_FARE_EUR = 5;
    private static final int MAX_RESULTS = 500;
    // The dead hours - deliberately narrower than "when it's dark". Landing at 23:30 and
    // leaving at 00:30 is an hour's wait that happens to cross midnight, not a night spent in
    // the terminal; a wait that reaches into 01:00-05:00 is.
    private static final LocalTime NIGHT_START = LocalTime.of(1, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(5, 0);

    @Value("${flight.search.min-connection-minutes:90}")
    private int minConnectionMinutes;

    @Value("${flight.search.max-connection-hours:12}")
    private int maxConnectionHours;

    private final FlightRepository flightRepository;
    private final RouteRepository routeRepository;
    private final AirportRepository airportRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;

    private final EurConverter eurConverter;

    public FlightSearchServiceImpl(FlightRepository flightRepository, RouteRepository routeRepository,
                                    AirportRepository airportRepository, PriceSnapshotRepository priceSnapshotRepository,
                                    EurConverter eurConverter) {
        this.flightRepository = flightRepository;
        this.routeRepository = routeRepository;
        this.airportRepository = airportRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.eurConverter = eurConverter;
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

    /**
     * The route and airport tables are reloaded at most this often rather than on every
     * search. They only change while collection is running - which the instance serving the
     * site doesn't do at all - but a search that matched nothing still spent 240ms of its
     * 325 loading six thousand routes to answer with an empty list. On the hosted instance,
     * with a fraction of a CPU to map those rows, the same work took seconds.
     */
    private static final Duration CONTEXT_TTL = Duration.ofMinutes(5);

    private volatile CachedContext cachedContext;

    private record CachedContext(SearchContext context, Instant builtAt) {}

    private SearchContext context() {
        CachedContext current = cachedContext;
        if (current != null && Duration.between(current.builtAt(), Instant.now()).compareTo(CONTEXT_TTL) < 0) {
            return current.context();
        }
        // Two searches arriving together may both rebuild; they produce the same thing, and
        // paying for it twice occasionally is cheaper than making every search wait on a lock.
        SearchContext rebuilt = buildContext();
        cachedContext = new CachedContext(rebuilt, Instant.now());
        return rebuilt;
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
        logger.info("Searching flights: from={}, to={}, departure={}..{}, return={}..{}, maxStops={}",
            request.from(), request.to(), request.departure(), request.departureRangeEnd(),
            request.returnDate(), request.returnRangeEnd(), request.maxStops());

        SearchContext ctx = context();
        Set<String> fromAirports = resolveAirports(request.from());
        boolean anywhere = isAnywhere(request.to());
        List<LocalDate> departureDates = dateRange(request.departure(), request.departureRangeEnd());

        List<SearchResult> allResults;
        if (request.returnDate() != null) {
            allResults = searchRoundTrip(fromAirports, anywhere, departureDates, request, ctx);
        } else {
            allResults = searchOneWay(fromAirports, anywhere, departureDates, request, ctx);
        }

        sortResults(allResults, request.sortBy());

        int found = allResults.size();
        if (found > MAX_RESULTS) {
            allResults = allResults.subList(0, MAX_RESULTS);
        }

        logger.info("Found {} flight paths in {}ms{}", found, System.currentTimeMillis() - startTime,
            found > MAX_RESULTS ? " (returning the top " + MAX_RESULTS + ")" : "");
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
        Set<String> toAirports = anywhere ? Set.of() : resolveAirports(request.to());
        return searchLegs(fromAirports, toAirports, rangeStart(departureDates), rangeEnd(departureDates), request, ctx);
    }

    /**
     * Every itinerary from any of {@code fromAirports} to any of {@code toAirports} (empty means
     * "anywhere"), in a fixed handful of queries regardless of how many airports are involved -
     * one for the direct flights, two more if connections are in scope. Iterating airport pairs
     * and querying per pair instead is what made a wide search ("all of Poland to all of Italy")
     * cost hundreds of round trips.
     */
    private List<SearchResult> searchLegs(Set<String> fromAirports, Set<String> toAirports,
                                           Instant start, Instant end, SearchRequest request, SearchContext ctx) {
        List<SearchResult> results = new ArrayList<>();
        if (fromAirports.isEmpty()) {
            return results;
        }
        boolean anywhere = toAirports.isEmpty();

        if (request.maxStops() >= 0) {
            List<FlightWithPrice> directFlights = inEuros(anywhere
                ? flightRepository.findFlightsFromAnyDestinationFromAirports(fromAirports, start, end)
                : flightRepository.findDirectFlightsBetweenAirports(fromAirports, toAirports, start, end));
            for (FlightWithPrice flight : filterByAirlines(directFlights, request.airlines(), ctx)) {
                results.add(createSearchResult(flight, ctx));
            }
        }

        if (request.maxStops() >= 1) {
            results.addAll(findOneStopFlights(fromAirports, toAirports, start, end, request, ctx));
        }

        return results;
    }

    // Finds every outbound option across the whole departure range in one pass, groups them by
    // the actual (origin, destination) pair flown, then for each such pair looks for a return
    // leg across the whole return range and pairs every outbound option with every matching
    // return option whose date isn't before its own outbound date. Grouping by the pair actually
    // flown is what makes this work for "Anywhere" too - the return leg only ever gets searched
    // back from wherever the outbound leg actually landed.
    //
    // allowReturnToDifferentAirport / allowReturnFromDifferentAirport relax which airports the
    // return leg is allowed to use: normally it must land back at the exact fromAirports airport
    // used and depart from the exact airport the outbound landed at. With either flag on, that
    // side of the pair is widened to "anywhere in the same searched group" instead of "exactly
    // this airport" - grouping outbound results on a wildcard for that side so the (usually much
    // smaller) return search only runs once per group instead of once per exact pair.
    private List<SearchResult> searchRoundTrip(Set<String> fromAirports, boolean anywhere,
                                                List<LocalDate> departureDates, SearchRequest request, SearchContext ctx) {
        List<LocalDate> returnDates = dateRange(request.returnDate(), request.returnRangeEnd());
        Instant departureStart = rangeStart(departureDates);
        Instant departureEnd = rangeEnd(departureDates);
        Instant returnStart = rangeStart(returnDates);
        Instant returnEnd = rangeEnd(returnDates);
        Set<String> toAirports = anywhere ? Set.of() : resolveAirports(request.to());
        boolean flexOrigin = request.allowReturnToDifferentAirport();
        boolean flexDestination = request.allowReturnFromDifferentAirport();

        List<SearchResult> outboundResults = searchLegs(fromAirports, toAirports, departureStart, departureEnd, request, ctx);
        if (outboundResults.isEmpty()) {
            return List.of();
        }

        // One batched return search covering every airport any outbound option actually reached
        // (and every airport a return could land back at), then matched up in memory below -
        // rather than a fresh return search per outbound origin/destination group.
        Set<String> allReturnOrigins = outboundResults.stream().map(this::destinationOf).collect(Collectors.toSet());
        Set<String> allReturnDestinations = flexOrigin
            ? fromAirports
            : outboundResults.stream().map(this::originOf).collect(Collectors.toSet());
        List<SearchResult> allReturns = searchLegs(allReturnOrigins, allReturnDestinations, returnStart, returnEnd, request, ctx);
        if (allReturns.isEmpty()) {
            return List.of();
        }
        Map<PairKey, List<SearchResult>> returnsByRoute = allReturns.stream()
            .collect(Collectors.groupingBy(r -> new PairKey(originOf(r), destinationOf(r))));

        Map<PairKey, List<SearchResult>> byPair = outboundResults.stream()
            .collect(Collectors.groupingBy(r -> pairKey(r, flexOrigin, flexDestination), LinkedHashMap::new, Collectors.toList()));

        // Pairing every outbound with every return is a cross product: a fortnight-wide search
        // across two countries produced 3.5 million itineraries, nearly all of them the same
        // trip with a different connection or departure time. Only the cheapest itinerary per
        // (route, outbound day, return day) is kept - that's the ~50-200 genuinely different
        // trips a person is choosing between - and it's kept while pairing rather than filtered
        // afterwards, so the discarded millions are never built in the first place.
        Map<TripKey, SearchResult> cheapestPerTrip = new LinkedHashMap<>();
        for (Map.Entry<PairKey, List<SearchResult>> entry : byPair.entrySet()) {
            List<SearchResult> group = entry.getValue();

            Set<String> returnOrigins = flexDestination ? allReturnOrigins : Set.of(entry.getKey().destination());
            Set<String> returnDestinations = flexOrigin ? allReturnDestinations : Set.of(entry.getKey().origin());

            List<SearchResult> returnResults = new ArrayList<>();
            for (String returnFrom : returnOrigins) {
                for (String returnTo : returnDestinations) {
                    returnResults.addAll(returnsByRoute.getOrDefault(new PairKey(returnFrom, returnTo), List.of()));
                }
            }
            if (returnResults.isEmpty()) {
                continue;
            }

            for (SearchResult outbound : group) {
                LocalDate outboundDate = outbound.departure().toLocalDate();
                // Time at the destination runs from landing, not from leaving home: an outbound
                // that lands the next day after an overnight connection spends that day in
                // transit. SearchResult.stayDays() counts it the same way, so what a card shows
                // is what this filtered on.
                LocalDate arrivalDate = outbound.arrival().toLocalDate();
                for (SearchResult ret : returnResults) {
                    LocalDate returnDate = ret.departure().toLocalDate();
                    if (returnDate.isBefore(arrivalDate)) {
                        continue;
                    }
                    long stayDays = ChronoUnit.DAYS.between(arrivalDate, returnDate);
                    if (request.stayMinDays() != null && stayDays < request.stayMinDays()) {
                        continue;
                    }
                    if (request.stayMaxDays() != null && stayDays > request.stayMaxDays()) {
                        continue;
                    }
                    TripKey key = new TripKey(originOf(outbound), destinationOf(outbound), outboundDate, returnDate);
                    SearchResult best = cheapestPerTrip.get(key);
                    double total = outbound.totalPrice() + ret.totalPrice();
                    if (best == null || total < best.totalPrice()) {
                        cheapestPerTrip.put(key, combineRoundTrip(outbound, ret));
                    }
                }
            }
        }

        return new ArrayList<>(cheapestPerTrip.values());
    }

    private record TripKey(String origin, String destination, LocalDate outboundDate, LocalDate returnDate) {}

    private record PairKey(String origin, String destination) {}

    private String originOf(SearchResult result) {
        return result.segments().get(0).fromAirport();
    }

    private String destinationOf(SearchResult result) {
        return result.segments().get(result.segments().size() - 1).toAirport();
    }

    // Wildcards ("*") the side that's flexible - flights that only differ on that side then
    // collapse into the same group, so return legs get matched once per group instead of once
    // per exact outbound pair.
    private PairKey pairKey(SearchResult result, boolean flexOrigin, boolean flexDestination) {
        return new PairKey(
            flexOrigin ? "*" : originOf(result),
            flexDestination ? "*" : destinationOf(result)
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

    private boolean isAnywhere(String to) {
        for (String token : to.split(",")) {
            if (ANYWHERE.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns either side of a search into the set of airports it covers. Both sides carry the
     * same comma-separated tokens - an airport code, "COUNTRY:x" / "CITY:x" for a whole country
     * or multi-airport city, or the "POLAND" / "WARSAW" shorthands - because the two sides can
     * be swapped, and a country that reads as a destination has to read as an origin too.
     */
    private Set<String> resolveAirports(String places) {
        List<String> tokens = new ArrayList<>();
        for (String rawToken : places.split(",")) {
            String token = rawToken.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        // Loaded at most once per search, and only when a COUNTRY:/CITY: token actually needs
        // it - a search with several such chips used to re-query the whole destination list once
        // per chip.
        List<AirportEntity> destinations = tokens.stream().anyMatch(this::needsDestinationList)
            ? reachableDestinations()
            : List.of();

        Set<String> result = new HashSet<>();
        for (String token : tokens) {
            if (WARSAW.equalsIgnoreCase(token)) {
                result.addAll(WARSAW_AIRPORTS);
            } else if (POLAND.equalsIgnoreCase(token)) {
                result.addAll(PolandAirports.ALL);
            } else if (token.regionMatches(true, 0, COUNTRY_PREFIX, 0, COUNTRY_PREFIX.length())) {
                String country = token.substring(COUNTRY_PREFIX.length());
                result.addAll(matching(destinations, a -> a.country().equalsIgnoreCase(country)));
            } else if (token.regionMatches(true, 0, CITY_PREFIX, 0, CITY_PREFIX.length())) {
                String city = token.substring(CITY_PREFIX.length());
                result.addAll(matching(destinations, a -> a.city().equalsIgnoreCase(city)));
            } else {
                result.add(token.toUpperCase());
            }
        }
        return result;
    }

    private boolean needsDestinationList(String token) {
        return token.regionMatches(true, 0, COUNTRY_PREFIX, 0, COUNTRY_PREFIX.length())
            || token.regionMatches(true, 0, CITY_PREFIX, 0, CITY_PREFIX.length());
    }

    private List<AirportEntity> reachableDestinations() {
        List<AirportEntity> destinations = new ArrayList<>();
        airportRepository.findDestinationsFrom(PolandAirports.ALL).forEach(destinations::add);
        return destinations;
    }

    private Set<String> matching(List<AirportEntity> destinations, Predicate<AirportEntity> filter) {
        return destinations.stream()
            .filter(filter)
            .map(AirportEntity::iata)
            .collect(Collectors.toSet());
    }

    // Two queries total: every first leg out of any origin, then every candidate connecting
    // flight (across all connection airports at once). Everything else is matched in memory.
    private List<SearchResult> findOneStopFlights(Set<String> fromAirports, Set<String> toAirports,
                                                    Instant start, Instant end, SearchRequest request, SearchContext ctx) {
        boolean anywhere = toAirports.isEmpty();
        Duration minConnectionTime = effectiveMinConnectionTime(request);
        Duration maxConnectionTime = effectiveMaxConnectionTime(request);

        List<FlightWithPrice> fromFlights = inEuros(
            flightRepository.findFlightsFromAnyDestinationFromAirports(fromAirports, start, end));
        fromFlights = filterByAirlines(fromFlights, request.airlines(), ctx);

        Map<String, List<FlightWithPrice>> firstLegsByConnection = groupByConnectionAirport(fromFlights, ctx);
        if (request.schengenConnectionsOnly()) {
            firstLegsByConnection.keySet().removeIf(airport -> !isSchengen(airport, ctx));
        }
        if (firstLegsByConnection.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> transferCandidates = transferCandidatesByConnection(firstLegsByConnection.keySet(), request, ctx);
        Set<String> allCandidateOrigins = transferCandidates.values().stream()
            .flatMap(Set::stream).collect(Collectors.toSet());

        Instant[] widestWindow = widestConnectionWindow(firstLegsByConnection.values(), minConnectionTime, maxConnectionTime);
        List<FlightWithPrice> candidateSecondLegs = inEuros(anywhere
            ? flightRepository.findFlightsFromAnyDestinationFromAirports(allCandidateOrigins, widestWindow[0], widestWindow[1])
            : flightRepository.findDirectFlightsBetweenAirports(allCandidateOrigins, toAirports, widestWindow[0], widestWindow[1]));
        candidateSecondLegs = filterByAirlines(candidateSecondLegs, request.airlines(), ctx);

        Map<String, List<FlightWithPrice>> secondLegsByOrigin = groupByFromAirport(candidateSecondLegs, ctx);

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, List<FlightWithPrice>> entry : firstLegsByConnection.entrySet()) {
            String connectionAirport = entry.getKey();
            List<FlightWithPrice> secondLegs = transferCandidates.get(connectionAirport).stream()
                .flatMap(origin -> secondLegsByOrigin.getOrDefault(origin, List.of()).stream())
                .toList();
            if (secondLegs.isEmpty()) {
                continue;
            }
            for (FlightWithPrice firstFlight : entry.getValue()) {
                Instant connectionFrom = firstFlight.arrival().plus(minConnectionTime);
                Instant connectionTo = firstFlight.arrival().plus(maxConnectionTime);
                for (FlightWithPrice secondFlight : secondLegs) {
                    if (!isWithin(secondFlight.departure(), connectionFrom, connectionTo)
                        || !connectionIsAcceptable(firstFlight.arrival(), secondFlight.departure(), request)) {
                        continue;
                    }
                    // Keeps a plain direct flight from being reported as a connection too, and
                    // rules out a second leg that just returns to where the first one landed.
                    String finalDestination = ctx.toAirport(secondFlight.routeId());
                    if (finalDestination == null || finalDestination.equals(connectionAirport)) {
                        continue;
                    }
                    results.add(createSearchResult(firstFlight, secondFlight, ctx));
                }
            }
        }

        return results;
    }

    private boolean connectionIsAcceptable(Instant arrival, Instant departure, SearchRequest request) {
        return request.allowOvernightConnection() || !isOvernightConnection(arrival, departure);
    }

    /**
     * Whether the wait between two flights runs through the night, which is what someone
     * ruling out overnight connections actually cares about - not whether the calendar date
     * changes. Landing at 01:15 and leaving at 05:45 is a single date but is a night spent in
     * the terminal; landing at 23:30 and leaving at 00:30 crosses midnight but is an hour's wait.
     */
    private boolean isOvernightConnection(Instant arrival, Instant departure) {
        LocalDateTime arrivalTime = LocalDateTime.ofInstant(arrival, ZoneOffset.UTC);
        LocalDateTime departureTime = LocalDateTime.ofInstant(departure, ZoneOffset.UTC);
        if (Duration.between(arrivalTime, departureTime).toHours() >= 24) {
            return true;
        }
        // A wait shorter than a day can only reach the dead hours of the arrival's own date or
        // of the following one.
        for (LocalDate day : List.of(arrivalTime.toLocalDate(), arrivalTime.toLocalDate().plusDays(1))) {
            if (arrivalTime.isBefore(day.atTime(NIGHT_END)) && departureTime.isAfter(day.atTime(NIGHT_START))) {
                return true;
            }
        }
        return false;
    }

    // Without ground transfer, the only valid second-leg origin for a given connection airport
    // is that airport itself. With it on, any airport within groundTransferRadiusKm also counts -
    // the existing connection-time window is what limits how long that transfer is allowed to
    // take, there's no separate transfer-time setting.
    private Map<String, Set<String>> transferCandidatesByConnection(Set<String> connectionAirports,
                                                                       SearchRequest request, SearchContext ctx) {
        Map<String, Set<String>> result = new HashMap<>();
        if (!request.allowGroundTransfer()) {
            for (String airport : connectionAirports) {
                result.put(airport, Set.of(airport));
            }
            return result;
        }
        double radiusKm = request.groundTransferRadiusKm() != null ? request.groundTransferRadiusKm() : 100;
        for (String airport : connectionAirports) {
            Set<String> nearby = nearbyAirports(airport, radiusKm, ctx);
            if (request.schengenConnectionsOnly()) {
                // Crossing to a nearby airport still means being there, so it has to be
                // somewhere the same visa covers - a hundred kilometres can cross a border.
                nearby.removeIf(candidate -> !isSchengen(candidate, ctx));
            }
            result.put(airport, nearby);
        }
        return result;
    }

    private boolean isSchengen(String iata, SearchContext ctx) {
        AirportEntity airport = ctx.airportsByIata().get(iata);
        return airport != null && Schengen.includes(airport.country());
    }

    private Set<String> nearbyAirports(String iata, double radiusKm, SearchContext ctx) {
        AirportEntity origin = ctx.airportsByIata().get(iata);
        Set<String> nearby = new HashSet<>();
        nearby.add(iata);
        if (origin == null || origin.lat() == null || origin.lon() == null) {
            return nearby;
        }
        for (AirportEntity candidate : ctx.airportsByIata().values()) {
            if (candidate.iata().equals(iata) || candidate.lat() == null || candidate.lon() == null) {
                continue;
            }
            if (haversineKm(origin.lat(), origin.lon(), candidate.lat(), candidate.lon()) <= radiusKm) {
                nearby.add(candidate.iata());
            }
        }
        return nearby;
    }

    private static final double EARTH_RADIUS_KM = 6371.0;

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    // Falls back to the configured default (application.yml) when the user's search didn't
    // specify a connection window - lets each search request override it (down to 30 minutes,
    // or out to several days for airlines like WizzAir whose collected data only carries a
    // placeholder time, not a real one) without changing the server-wide default.
    private Duration effectiveMinConnectionTime(SearchRequest request) {
        Integer minutes = request.minConnectionMinutes();
        return Duration.ofMinutes(minutes != null ? minutes : minConnectionMinutes);
    }

    private Duration effectiveMaxConnectionTime(SearchRequest request) {
        Integer minutes = request.maxConnectionMinutes();
        return Duration.ofMinutes(minutes != null ? minutes : maxConnectionHours * 60L);
    }

    private boolean isWithin(Instant value, Instant from, Instant to) {
        return !value.isBefore(from) && !value.isAfter(to);
    }

    // Groups first-leg flights by the airport they land at (the potential connection point).
    // Landing at one of the search's own destinations is not disqualifying - flying into
    // Barcelona and on to Malaga is a real connection when the search covers all of Spain -
    // so what keeps a direct flight from also being counted as a connection is the
    // "final destination != connection airport" check at the pairing step, not this grouping.
    private Map<String, List<FlightWithPrice>> groupByConnectionAirport(List<FlightWithPrice> flights, SearchContext ctx) {
        Map<String, List<FlightWithPrice>> byConnection = new HashMap<>();
        for (FlightWithPrice flight : flights) {
            String connectionAirport = ctx.toAirport(flight.routeId());
            if (connectionAirport == null) {
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
        // Normally equal to firstTo, but with ground transfer allowed the second leg can depart
        // from a different (nearby) airport than the one the first leg landed at.
        String secondFrom = ctx.fromAirport(secondFlight.routeId());
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

    /**
     * Every price a search works with, converted from the currency the airline quoted into euros.
     *
     * <p>This is the one place it happens, deliberately: everything downstream adds prices across
     * legs, compares them, and sorts by them, none of which means anything across currencies -
     * one line already sums two legs and keeps the first one's currency label, which was harmless
     * only as long as everything was euros. Normalising on the way in keeps that true.
     *
     * <p>It is also where implausibly cheap rows are dropped. That filter used to live in SQL as
     * "price >= 5", which quietly stopped meaning anything the moment prices arrived in forints
     * as well as euros; five of one is a fare and five of the other is small change. Judged after
     * conversion it means what it was always meant to mean - no real budget-airline fare is a
     * euro or two.
     */
    private List<FlightWithPrice> inEuros(List<FlightWithPrice> flights) {
        List<FlightWithPrice> converted = new ArrayList<>(flights.size());
        Set<String> unconvertible = new HashSet<>();
        for (FlightWithPrice flight : flights) {
            Optional<Double> euros = eurConverter.toEur(flight.price(), flight.currency());
            if (euros.isEmpty()) {
                unconvertible.add(flight.currency());
                continue;
            }
            if (euros.get() < CHEAPEST_PLAUSIBLE_FARE_EUR) {
                continue;
            }
            converted.add(new FlightWithPrice(flight.id(), flight.routeId(), flight.flightNumber(),
                flight.departure(), flight.arrival(), flight.updatedAt(), euros.get(), "EUR"));
        }
        if (!unconvertible.isEmpty()) {
            logger.warn("Left {} out of the results - no exchange rate to show them in euros", unconvertible);
        }
        return converted;
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
