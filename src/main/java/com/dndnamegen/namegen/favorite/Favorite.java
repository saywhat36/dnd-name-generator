package com.dndnamegen.namegen.favorite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "favorites")
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_id", nullable = false)
    private Long nameId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Favorite() {}

    public Favorite(String sessionId, Long nameId) {
        this.sessionId = sessionId;
        this.nameId = nameId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getNameId() {
        return nameId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
