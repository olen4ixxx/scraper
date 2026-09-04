package org.example.flightsearch.db.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("flight")
public record FlightEntity(
    @Id Long id,
    Long routeId,
    String flightNumber,
    Instant departure,
    Instant arrival,
    Instant updatedAt,
    // Whether the airline published clock times, or whether the two above are placeholders for
    // a fare that arrived with nothing but a date. See FlightDto.
    Boolean timeKnown
) {}
