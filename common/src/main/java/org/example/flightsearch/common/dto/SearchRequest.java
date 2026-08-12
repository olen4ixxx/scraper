package org.example.flightsearch.common.dto;

import org.example.flightsearch.common.model.Airline;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record SearchRequest(
    String from,
    String to,
    LocalDate departure,
    LocalDate departureRangeEnd,
    LocalDate returnDate,
    LocalDate returnRangeEnd,
    boolean directOnly,
    int maxStops,
    Set<Airline> airlines,
    SortBy sortBy
) {
    public enum SortBy {
        CHEAPEST,
        SHORTEST,
        EARLIEST_DEPARTURE,
        LATEST_DEPARTURE,
        FEWEST_STOPS
    }
    
    public SearchRequest {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("From airport cannot be blank");
        }
        if (departure == null){
            throw new IllegalArgumentException("Departure date cannot be null");
        }
        if (maxStops < 0) {
            throw new IllegalArgumentException("Max stops cannot be negative");
        }
        if (sortBy == null) {
            sortBy = SortBy.CHEAPEST;
        }
    }
}
