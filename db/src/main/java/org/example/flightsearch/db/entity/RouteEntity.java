package org.example.flightsearch.db.entity;

import org.example.flightsearch.common.model.Airline;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("route")
public record RouteEntity(
    @Id Long id,
    Airline airline,
    String fromAirport,
    String toAirport,
    Boolean active,
    // When collection last tried this route - not when it last succeeded. Drives the order a
    // pass visits routes in, so one that cannot finish still rotates through the network.
    Instant lastAttemptedAt
) {}
