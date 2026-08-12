package org.example.flightsearch.common.model;

public record Airport(
    Long id,
    String iata,
    String name,
    String city,
    String country,
    Double lat,
    Double lon
) {
    public Airport {
        if (iata == null || iata.isBlank()) {
            throw new IllegalArgumentException("IATA code cannot be blank");
        }
    }
}
