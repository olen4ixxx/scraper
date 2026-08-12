package org.example.flightsearch.db.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("airport")
public record AirportEntity(
    @Id Long id,
    String iata,
    String name,
    String city,
    String country,
    Double lat,
    Double lon
) {}
