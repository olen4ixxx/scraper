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
    String currency
) {}
