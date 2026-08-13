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
    int maxStops,
    Set<Airline> airlines,
    SortBy sortBy,
    Integer minConnectionMinutes,
    Integer maxConnectionMinutes,
    // How many days between outbound and return - null means unrestricted (governed only by
    // the departure/return date ranges themselves).
    Integer stayMinDays,
    Integer stayMaxDays,
    // Off by default (strict): a connection must depart the same calendar day it lands.
    boolean allowOvernightConnection,
    // Off by default (strict): the return leg must land back at the exact airport the outbound
    // leg departed from / depart from the exact airport the outbound leg landed at. On, either
    // side is allowed to differ within the same searched airport group (e.g. fly out of WMI,
    // back into KRK; or out of any airport in the destination country).
    boolean allowReturnToDifferentAirport,
    boolean allowReturnFromDifferentAirport,
    // Off by default: a connection requires landing and departing from the exact same airport.
    // On, a nearby airport (within groundTransferRadiusKm) also counts as a valid connection
    // point - the existing connection-time window still governs how long that transfer can take.
    boolean allowGroundTransfer,
    Integer groundTransferRadiusKm
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
