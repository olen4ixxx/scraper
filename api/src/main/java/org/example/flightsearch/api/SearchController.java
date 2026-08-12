package org.example.flightsearch.api;

import org.example.flightsearch.common.dto.PriceHistoryPoint;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;
import org.example.flightsearch.common.model.Airline;
import org.example.flightsearch.search.FlightSearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
public class SearchController {
    
    private final FlightSearchService searchService;
    
    public SearchController(FlightSearchService searchService) {
        this.searchService = searchService;
    }
    
    @GetMapping("/api/search")
    public List<SearchResult> search(
        @RequestParam String from,
        @RequestParam String to,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departure,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureRangeEnd,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnRangeEnd,
        @RequestParam(defaultValue = "false") boolean directOnly,
        @RequestParam(defaultValue = "1") int maxStops,
        @RequestParam(required = false) Set<Airline> airlines,
        @RequestParam(defaultValue = "CHEAPEST") SearchRequest.SortBy sortBy,
        @RequestParam(required = false) Integer minConnectionMinutes,
        @RequestParam(required = false) Integer maxConnectionMinutes
    ) {
        SearchRequest request = new SearchRequest(
            from, to, departure, departureRangeEnd, returnDate, returnRangeEnd, directOnly, maxStops, airlines, sortBy,
            minConnectionMinutes, maxConnectionMinutes
        );
        return searchService.search(request);
    }

    @GetMapping("/api/priceHistory")
    public List<PriceHistoryPoint> priceHistory(@RequestParam Long flightId) {
        return searchService.getPriceHistory(flightId);
    }
}
