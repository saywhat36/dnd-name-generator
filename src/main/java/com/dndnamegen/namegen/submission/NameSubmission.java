package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.generation.DeduplicationService;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "name_submissions")
public class NameSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "race", nullable = false)
    private Race race;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubmissionStatus status;

    @Column(name = "submitter_id", nullable = false)
    private Long submitterId;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NameSubmission() {}

    public NameSubmission(Long submitterId, String displayName, Race race, Gender gender) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
        this.submitterId = Objects.requireNonNull(submitterId, "submitterId must not be null");
        this.displayName = displayName;
        this.normalizedName = DeduplicationService.normalize(displayName);
        this.race = Objects.requireNonNull(race, "race must not be null");
        this.gender = Objects.requireNonNull(gender, "gender must not be null");
        this.status = SubmissionStatus.PENDING;
        this.createdAt = Instant.now();
    }

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

    public SubmissionStatus getStatus() {
        return status;
    }

    public Long getSubmitterId() {
        return submitterId;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}