package org.example.flightsearch.app.controller;

import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.common.model.PolandAirports;
import org.example.flightsearch.db.repository.AirportRepository;
import org.example.flightsearch.db.repository.FlightRepository;
import org.example.flightsearch.db.repository.PriceSnapshotRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.example.flightsearch.app.service.SearchFormOptions;
import org.example.flightsearch.search.FlightSearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Controller
public class WebController {
    
    private final FlightSearchService searchService;
    private final AirportRepository airportRepository;
    private final RouteRepository routeRepository;
    private final FlightRepository flightRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final SearchFormOptions formOptions;

    public WebController(
        FlightSearchService searchService,
        AirportRepository airportRepository,
        RouteRepository routeRepository,
        FlightRepository flightRepository,
        PriceSnapshotRepository priceSnapshotRepository,
        SearchFormOptions formOptions
    ) {
        this.searchService = searchService;
        this.airportRepository = airportRepository;
        this.routeRepository = routeRepository;
        this.flightRepository = flightRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.formOptions = formOptions;
    }
    
    @GetMapping("/")
    public String index(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departure,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureRangeEnd,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnRangeEnd,
        @RequestParam(required = false) Integer maxStops,
        @RequestParam(required = false) List<String> airlines,
        @RequestParam(required = false) SearchRequest.SortBy sortBy,
        @RequestParam(required = false) Integer minConnectionMinutes,
        @RequestParam(required = false) Integer maxConnectionMinutes,
        @RequestParam(required = false) Integer stayMinDays,
        @RequestParam(required = false) Integer stayMaxDays,
        @RequestParam(required = false) Boolean allowOvernightConnection,
        @RequestParam(required = false) Boolean allowReturnToDifferentAirport,
        @RequestParam(required = false) Boolean allowReturnFromDifferentAirport,
        @RequestParam(required = false) Boolean allowGroundTransfer,
        @RequestParam(required = false) Integer groundTransferRadiusKm,
        @RequestParam(required = false) Boolean schengenConnectionsOnly,
        Model model
    ) {
        model.addAttribute("destinations", formOptions.destinations());
        model.addAttribute("availableAirlines", formOptions.airlines());

        // Non-null only when arriving here via the results page's "Back to search" link -
        // lets the form re-populate itself instead of resetting to defaults. Dates go over
        // as plain ISO strings (LocalDate.toString()) rather than raw LocalDate objects, to
        // not depend on how Thymeleaf's JS-inlining happens to serialize java.time types.
        model.addAttribute("prefillFrom", from);
        model.addAttribute("prefillTo", to);
        model.addAttribute("prefillDeparture", departure != null ? departure.toString() : null);
        model.addAttribute("prefillDepartureRangeEnd", departureRangeEnd != null ? departureRangeEnd.toString() : null);
        model.addAttribute("prefillReturnDate", returnDate != null ? returnDate.toString() : null);
        model.addAttribute("prefillReturnRangeEnd", returnRangeEnd != null ? returnRangeEnd.toString() : null);
        model.addAttribute("prefillMaxStops", maxStops);
        model.addAttribute("prefillAirlines", airlines);
        model.addAttribute("prefillSortBy", sortBy != null ? sortBy.name() : null);
        model.addAttribute("prefillMinConnectionMinutes", minConnectionMinutes);
        model.addAttribute("prefillMaxConnectionMinutes", maxConnectionMinutes);
        model.addAttribute("prefillStayMinDays", stayMinDays);
        model.addAttribute("prefillStayMaxDays", stayMaxDays);
        model.addAttribute("prefillAllowOvernightConnection", allowOvernightConnection != null && allowOvernightConnection);
        model.addAttribute("prefillAllowReturnToDifferentAirport", allowReturnToDifferentAirport != null && allowReturnToDifferentAirport);
        model.addAttribute("prefillAllowReturnFromDifferentAirport", allowReturnFromDifferentAirport != null && allowReturnFromDifferentAirport);
        model.addAttribute("prefillAllowGroundTransfer", allowGroundTransfer != null && allowGroundTransfer);
        model.addAttribute("prefillGroundTransferRadiusKm", groundTransferRadiusKm);
        model.addAttribute("prefillSchengenConnectionsOnly", schengenConnectionsOnly != null && schengenConnectionsOnly);
        // Lets the form tell a fresh visit from a return trip through "Back to search", so
        // defaults apply to the first and the previous choices to the second.
        model.addAttribute("hasPreviousSearch", to != null);

        return "index";
    }
    
    @GetMapping("/results")
    public String results(
        @RequestParam String from,
        @RequestParam String to,
        @RequestParam @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate departure,
        @RequestParam(required = false) @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate departureRangeEnd,
        @RequestParam(required = false) @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate returnDate,
        @RequestParam(required = false) @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate returnRangeEnd,
        @RequestParam(defaultValue = "1") int maxStops,
        @RequestParam(required = false) List<String> airlines,
        @RequestParam(defaultValue = "CHEAPEST") SearchRequest.SortBy sortBy,
        @RequestParam(required = false) Integer minConnectionMinutes,
        @RequestParam(required = false) Integer maxConnectionMinutes,
        @RequestParam(required = false) Integer stayMinDays,
        @RequestParam(required = false) Integer stayMaxDays,
        @RequestParam(defaultValue = "false") boolean allowOvernightConnection,
        @RequestParam(defaultValue = "false") boolean allowReturnToDifferentAirport,
        @RequestParam(defaultValue = "false") boolean allowReturnFromDifferentAirport,
        @RequestParam(defaultValue = "false") boolean allowGroundTransfer,
        @RequestParam(required = false) Integer groundTransferRadiusKm,
        @RequestParam(defaultValue = "false") boolean schengenConnectionsOnly,
        Model model
    ) {
        Set<Airline> airlineSet = new HashSet<>();
        if (airlines != null) {
            for (String airline : airlines) {
                try {
                    airlineSet.add(Airline.valueOf(airline.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    // Ignore invalid airline names
                }
            }
        }

        SearchRequest request = new SearchRequest(
            from, to, departure, departureRangeEnd, returnDate, returnRangeEnd, maxStops, airlineSet, sortBy,
            minConnectionMinutes, maxConnectionMinutes, stayMinDays, stayMaxDays, allowOvernightConnection,
            allowReturnToDifferentAirport, allowReturnFromDifferentAirport, allowGroundTransfer, groundTransferRadiusKm, schengenConnectionsOnly
        );

        List<SearchResult> results = searchService.search(request);

        model.addAttribute("from", formatFromDisplay(from));
        model.addAttribute("to", formatToDisplay(to));
        model.addAttribute("departure", departure);
        model.addAttribute("returnDate", returnDate);
        model.addAttribute("results", results);

        // Raw (unformatted) search params, for the "Back to search" link to carry the
        // form back to how it was instead of resetting to defaults.
        model.addAttribute("qFrom", from);
        model.addAttribute("qTo", to);
        model.addAttribute("qDeparture", departure);
        model.addAttribute("qDepartureRangeEnd", departureRangeEnd);
        model.addAttribute("qReturnDate", returnDate);
        model.addAttribute("qReturnRangeEnd", returnRangeEnd);
        model.addAttribute("qMaxStops", maxStops);
        model.addAttribute("qAirlines", airlines);
        model.addAttribute("qSortBy", sortBy);
        model.addAttribute("qMinConnectionMinutes", minConnectionMinutes);
        model.addAttribute("qMaxConnectionMinutes", maxConnectionMinutes);
        model.addAttribute("qStayMinDays", stayMinDays);
        model.addAttribute("qStayMaxDays", stayMaxDays);
        model.addAttribute("qAllowOvernightConnection", allowOvernightConnection);
        model.addAttribute("qAllowReturnToDifferentAirport", allowReturnToDifferentAirport);
        model.addAttribute("qAllowReturnFromDifferentAirport", allowReturnFromDifferentAirport);
        model.addAttribute("qAllowGroundTransfer", allowGroundTransfer);
        model.addAttribute("qGroundTransferRadiusKm", groundTransferRadiusKm);
        model.addAttribute("qSchengenConnectionsOnly", schengenConnectionsOnly);

        return "results";
    }
    
    @GetMapping("/admin")
    public String admin(Model model) {
        long airportCount = airportRepository.count();
        long routeCount = routeRepository.count();
        long flightCount = flightRepository.count();
        long priceSnapshotCount = priceSnapshotRepository.count();
        
        model.addAttribute("stats", new AdminStats(
            airportCount,
            routeCount,
            flightCount,
            priceSnapshotCount,
            "Not available",
            "Not available"
        ));
        
        return "admin";
    }
    
    private String formatFromDisplay(String from) {
        String upper = from.toUpperCase();
        if ("WARSAW".equals(upper)) {
            return "Warsaw (WAW + WMI)";
        }
        if ("POLAND".equals(upper)) {
            return "Poland (all airports)";
        }
        return airportRepository.findByIata(upper)
            .map(a -> a.city() + " (" + a.iata() + ")")
            .orElse(from);
    }

    private String formatToDisplay(String to) {
        List<String> parts = new ArrayList<>();
        for (String rawToken : to.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            String upper = token.toUpperCase();
            if ("ANYWHERE".equals(upper)) {
                parts.add("Anywhere");
            } else if (upper.startsWith("COUNTRY:")) {
                parts.add(token.substring(8) + " (all)");
            } else if (upper.startsWith("CITY:")) {
                parts.add(token.substring(5) + " (all airports)");
            } else {
                parts.add(airportRepository.findByIata(upper)
                    .map(a -> a.city() + " (" + a.iata() + ")")
                    .orElse(token));
            }
        }
        return parts.isEmpty() ? to : String.join(", ", parts);
    }
    
    record AdminStats(
        long airports,
        long routes,
        long flights,
        long priceSnapshots,
        String lastWizzUpdate,
        String lastRyanairUpdate
    ) {}
}
