package org.example.flightsearch.common.dto;

import java.time.LocalDateTime;

public record FlightDto(
    String flightNumber,
    LocalDateTime departure,
    LocalDateTime arrival,
    Double price,
    String currency
) {}
