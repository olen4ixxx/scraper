package org.example.flightsearch.db.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("collector_setting")
public record CollectorSettingEntity(
    @Id String name,
    String value,
    Instant updatedAt
) {}
