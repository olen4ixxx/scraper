package org.example.flightsearch.common.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public record SearchResult(
    double totalPrice,
    String currency,
    List<String> airlines,
    LocalDateTime departure,
    LocalDateTime arrival,
    Duration duration,
    int numberOfStops,
    List<Segment> segments,
    // Round-trip return leg - null/empty for one-way results.
    LocalDateTime returnDeparture,
    LocalDateTime returnArrival,
    Duration returnDuration,
    int returnNumberOfStops,
    List<Segment> returnSegments
) {
    public boolean isRoundTrip() {
        return returnSegments != null && !returnSegments.isEmpty();
    }

    public record Segment(
        Long flightId,
        String airline,
        String fromAirport,
        String fromCity,
        String toAirport,
        String toCity,
        LocalDateTime departure,
        LocalDateTime arrival,
        double price,
        String currency,
        Duration duration
    ) {}
}
