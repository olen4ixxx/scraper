package org.example.flightsearch.common.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    /**
     * Days actually at the destination: from the day the outbound flight lands to the day the
     * return one leaves. Counting from the outbound's departure instead would overstate a trip
     * whose outbound lands the next day after an overnight connection - the traveller is still
     * in transit, not there.
     *
     * <p>The stay-length filter uses the same arithmetic, so a trip shown as five days is one
     * a search for five days finds.
     */
    public long stayDays() {
        if (!isRoundTrip()) {
            return 0;
        }
        return ChronoUnit.DAYS.between(arrival.toLocalDate(), returnDeparture.toLocalDate());
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
