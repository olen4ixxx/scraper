package org.example.flightsearch.db.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("saved_search")
public record SavedSearchEntity(
    @Id String name,
    String request,
    Instant createdAt,
    Instant lastUsedAt
) {}
