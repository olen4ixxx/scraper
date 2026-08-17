package org.example.flightsearch.app.controller;

import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.db.repository.AirportRepository;
import org.example.flightsearch.db.repository.FlightRepository;
import org.example.flightsearch.db.repository.PriceSnapshotRepository;
import org.example.flightsearch.db.repository.RouteRepository;
import org.example.flightsearch.app.service.SavedSearches;
import org.example.flightsearch.app.service.SearchFormOptions;
import org.example.flightsearch.search.FlightSearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Controller
public class WebController {

    private final FlightSearchService searchService;
    private final AirportRepository airportRepository;
    private final RouteRepository routeRepository;
    private final FlightRepository flightRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final SearchFormOptions formOptions;
    private final SavedSearches savedSearches;

    public WebController(
        FlightSearchService searchService,
        AirportRepository airportRepository,
        RouteRepository routeRepository,
        FlightRepository flightRepository,
        PriceSnapshotRepository priceSnapshotRepository,
        SearchFormOptions formOptions,
        SavedSearches savedSearches
    ) {
        this.searchService = searchService;
        this.airportRepository = airportRepository;
        this.routeRepository = routeRepository;
        this.flightRepository = flightRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.formOptions = formOptions;
        this.savedSearches = savedSearches;
    }

    /**
     * @param search the name of a previous search, as carried by the results page's "back to
     *               search" link. Present means the form should come back the way it was left
     *               rather than resetting to defaults.
     */
    @GetMapping("/")
    public String index(@RequestParam(name = "s", required = false) String search, Model model) {
        model.addAttribute("destinations", formOptions.destinations());
        model.addAttribute("availableAirlines", formOptions.airlines());

        Optional<SearchRequest> previous = search == null ? Optional.empty() : savedSearches.find(search);
        prefill(model, previous.orElse(null));
        // Lets the form tell a fresh visit from a return trip through "back to search", so
        // defaults apply to the first and the previous choices to the second.
        model.addAttribute("hasPreviousSearch", previous.isPresent());

        return "index";
    }

    /**
     * Where the search form submits. Answers with a redirect rather than the results themselves,
     * so the address bar ends up showing the short name of the search instead of the nineteen
     * parameters it is made of - and so reloading the results page re-reads them rather than
     * asking the browser to send the form again.
     */
    @PostMapping("/results")
    public String runSearch(
        @RequestParam String from,
        @RequestParam String to,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departure,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureRangeEnd,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnRangeEnd,
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
        @RequestParam(defaultValue = "false") boolean schengenConnectionsOnly
    ) {
        SearchRequest request = new SearchRequest(
            from, to, departure, departureRangeEnd, returnDate, returnRangeEnd, maxStops,
            airlineSet(airlines), sortBy, minConnectionMinutes, maxConnectionMinutes,
            stayMinDays, stayMaxDays, allowOvernightConnection, allowReturnToDifferentAirport,
            allowReturnFromDifferentAirport, allowGroundTransfer, groundTransferRadiusKm,
            schengenConnectionsOnly
        );
        return "redirect:/results/" + savedSearches.save(request);
    }

    @GetMapping("/results/{search}")
    public String results(@PathVariable("search") String search, Model model) {
        // A name this instance doesn't know - an old link, or one from before a restart. The
        // search form is a better answer than an error page: everything needed to ask again is
        // on it.
        return savedSearches.find(search)
            .map(request -> render(request, search, model))
            .orElse("redirect:/");
    }

    /**
     * The long-form address the form used to submit to, kept for links made before searches had
     * names. It runs the search by name like everything else, which also quietly tidies the
     * address of anyone following an old bookmark.
     */
    @GetMapping("/results")
    public String legacyResults(
        @RequestParam String from,
        @RequestParam String to,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departure,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureRangeEnd,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnRangeEnd,
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
        @RequestParam(defaultValue = "false") boolean schengenConnectionsOnly
    ) {
        return runSearch(from, to, departure, departureRangeEnd, returnDate, returnRangeEnd,
            maxStops, airlines, sortBy, minConnectionMinutes, maxConnectionMinutes,
            stayMinDays, stayMaxDays, allowOvernightConnection, allowReturnToDifferentAirport,
            allowReturnFromDifferentAirport, allowGroundTransfer, groundTransferRadiusKm,
            schengenConnectionsOnly);
    }

    private String render(SearchRequest request, String search, Model model) {
        // Keeps an address that people still follow from being swept up as unused.
        savedSearches.markUsed(search);
        List<SearchResult> results = searchService.search(request);

        model.addAttribute("from", formatFromDisplay(request.from()));
        model.addAttribute("to", formatToDisplay(request.to()));
        model.addAttribute("departure", request.departure());
        model.addAttribute("returnDate", request.returnDate());
        model.addAttribute("results", results);
        // All the "back to search" link needs: one name in place of the nineteen parameters it
        // used to have to carry.
        model.addAttribute("search", search);

        return "results";
    }

    private void prefill(Model model, SearchRequest previous) {
        model.addAttribute("prefillFrom", previous == null ? null : previous.from());
        model.addAttribute("prefillTo", previous == null ? null : previous.to());
        model.addAttribute("prefillDeparture", date(previous == null ? null : previous.departure()));
        model.addAttribute("prefillDepartureRangeEnd", date(previous == null ? null : previous.departureRangeEnd()));
        model.addAttribute("prefillReturnDate", date(previous == null ? null : previous.returnDate()));
        model.addAttribute("prefillReturnRangeEnd", date(previous == null ? null : previous.returnRangeEnd()));
        model.addAttribute("prefillMaxStops", previous == null ? null : previous.maxStops());
        model.addAttribute("prefillAirlines", airlineNames(previous));
        model.addAttribute("prefillSortBy", previous == null || previous.sortBy() == null
            ? null : previous.sortBy().name());
        model.addAttribute("prefillMinConnectionMinutes", previous == null ? null : previous.minConnectionMinutes());
        model.addAttribute("prefillMaxConnectionMinutes", previous == null ? null : previous.maxConnectionMinutes());
        model.addAttribute("prefillStayMinDays", previous == null ? null : previous.stayMinDays());
        model.addAttribute("prefillStayMaxDays", previous == null ? null : previous.stayMaxDays());
        model.addAttribute("prefillAllowOvernightConnection", previous != null && previous.allowOvernightConnection());
        model.addAttribute("prefillAllowReturnToDifferentAirport",
            previous != null && previous.allowReturnToDifferentAirport());
        model.addAttribute("prefillAllowReturnFromDifferentAirport",
            previous != null && previous.allowReturnFromDifferentAirport());
        model.addAttribute("prefillAllowGroundTransfer", previous != null && previous.allowGroundTransfer());
        model.addAttribute("prefillGroundTransferRadiusKm", previous == null ? null : previous.groundTransferRadiusKm());
        model.addAttribute("prefillSchengenConnectionsOnly", previous != null && previous.schengenConnectionsOnly());
    }

    /**
     * Dates go to the template as plain ISO strings rather than raw LocalDate objects, to not
     * depend on how Thymeleaf's JS-inlining happens to serialize java.time types.
     */
    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static List<String> airlineNames(SearchRequest previous) {
        if (previous == null || previous.airlines() == null || previous.airlines().isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Airline airline : previous.airlines()) {
            names.add(airline.name());
        }
        return names;
    }

    /**
     * An EnumSet rather than a HashSet, so the same choice of airlines always comes out in the
     * same order. A saved search is compared against the stored one to decide whether it already
     * has a name, and an unordered set would make two identical searches look different.
     */
    private static Set<Airline> airlineSet(List<String> airlines) {
        Set<Airline> parsed = EnumSet.noneOf(Airline.class);
        if (airlines != null) {
            for (String airline : airlines) {
                try {
                    parsed.add(Airline.valueOf(airline.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    // Ignore invalid airline names
                }
            }
        }
        return parsed;
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
