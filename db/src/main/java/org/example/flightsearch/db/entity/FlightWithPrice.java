package org.example.flightsearch.db.entity;

import java.time.Instant;

public record FlightWithPrice(
    Long id,
    Long routeId,
    String flightNumber,
    Instant departure,
    Instant arrival,
    Instant updatedAt,
    Double price,
    String currency,
    // Whether the airline published clock times for this flight - see FlightDto. Null for a row
    // written before the column existed, which reads as published.
    Boolean timeKnown
) {}
