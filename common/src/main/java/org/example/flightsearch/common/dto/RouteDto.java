package org.example.flightsearch.common.dto;

import org.example.flightsearch.common.model.Airline;

public record RouteDto(
    Long id,
    Airline airline,
    String fromAirport,
    String toAirport
) {}
