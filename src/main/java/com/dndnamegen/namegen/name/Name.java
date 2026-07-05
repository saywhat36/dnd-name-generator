package com.dndnamegen.namegen.name;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "names")
public class Name {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Race race;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NameSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NameStatus status;

    private String provider;

    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "generation_log_id")
    private Long generationLogId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Name() {}

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public Race getRace() {
        return race;
    }

    public Gender getGender() {
        return gender;
    }

    public NameSource getSource() {
        return source;
    }

    public NameStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Long getGenerationLogId() {
        return generationLogId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
