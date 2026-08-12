package org.example.flightsearch.db.entity;

import org.example.flightsearch.common.model.Airline;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("route")
public record RouteEntity(
    @Id Long id,
    Airline airline,
    String fromAirport,
    String toAirport,
    Boolean active
) {}
