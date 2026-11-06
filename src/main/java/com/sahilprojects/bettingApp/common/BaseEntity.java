package com.sahilprojects.bettingApp.common;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Shared columns for every mutable table: id, created_at, updated_at, version. */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    protected void onBaseCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onBaseUpdate() {
        updatedAt = Instant.now();
    }
}
