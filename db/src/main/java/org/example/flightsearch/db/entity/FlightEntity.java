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
    Instant updatedAt
) {}
