package org.example.flightsearch.common.dto;

import java.time.Instant;

public record PriceHistoryPoint(
    Instant collectedAt,
    double price,
    String currency
) {}
