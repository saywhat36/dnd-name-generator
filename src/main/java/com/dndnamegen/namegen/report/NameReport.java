package com.dndnamegen.namegen.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "name_reports")
public class NameReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_id", nullable = false)
    private Long nameId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NameReport() {}

    public NameReport(String sessionId, Long nameId, String reason) {
        this.sessionId = sessionId;
        this.nameId = nameId;
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
