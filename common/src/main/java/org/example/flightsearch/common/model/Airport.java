package org.example.flightsearch.common.model;

public record Airport(
    Long id,
    String iata,
    String name,
    String city,
    String country,
    Double lat,
    Double lon,
    // The IANA zone the airport keeps its clocks by. Airlines quote local times at both ends,
    // so without this a flight from Poland to Britain reads as an hour shorter than it is and
    // one from Spain to Morocco arrives before it left.
    String timezone
) {
    public Airport {
        if (iata == null || iata.isBlank()) {
            throw new IllegalArgumentException("IATA code cannot be blank");
        }
    }
}
