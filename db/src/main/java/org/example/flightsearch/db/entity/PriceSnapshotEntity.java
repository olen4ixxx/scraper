package org.example.flightsearch.db.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("price_snapshot")
public record PriceSnapshotEntity(
    @Id Long id,
    Long flightId,
    Double price,
    String currency,
    Instant collectedAt
) {}
