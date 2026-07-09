package com.dndnamegen.namegen.favorite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The {@code favorites} table still has a nullable {@code session_id} column from before
 * authentication was required (see V1's schema, docs/DECISIONS.md), but every favorite is
 * owner-keyed now -- there is no anonymous fallback, so nothing in this codebase ever
 * constructs or reads a session-keyed row anymore. Left unmapped here deliberately, not dropped
 * from the schema: {@code ddl-auto: validate} only requires mapped columns to exist, not the
 * reverse, so any pre-existing session-keyed rows are simply untouched, orphaned data.
 */
@Entity
@Table(name = "favorites")
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_id", nullable = false)
    private Long nameId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Favorite() {}

    public Favorite(Long ownerId, Long nameId) {
        this.ownerId = ownerId;
        this.nameId = nameId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getNameId() {
        return nameId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
