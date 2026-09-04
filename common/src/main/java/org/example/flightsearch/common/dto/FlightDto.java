package org.example.flightsearch.common.dto;

import java.time.LocalDateTime;

public record FlightDto(
    String flightNumber,
    LocalDateTime departure,
    LocalDateTime arrival,
    Double price,
    String currency,
    /**
     * Whether the airline published clock times for this flight, or whether the times above are a
     * placeholder standing in for a fare that came with nothing but a date.
     *
     * <p>Three of the five publish a date and an amount and no time of day at all. Those fares
     * were stored as the ends of their own day - departing 23:59, arriving 00:01 - which reads as
     * a flight that lands twenty-four hours before it takes off, and the results page duly showed
     * "-23h -58m". Worse than looking wrong, it counted: a connection is judged by how long there
     * is between one leg landing and the next leaving, so an invented arrival makes an invented
     * connection, and a flight that really lands at ten in the evening was being offered as a
     * comfortable change onto an eight o'clock morning departure.
     *
     * <p>Recorded rather than inferred from the placeholder values on purpose. Reading intent out
     * of the shape of data is how this project lost nine days to a 404 that looked like an empty
     * answer and a pass to a response envelope that changed - a flag cannot be misread that way.
     */
    boolean timeKnown
) {}
