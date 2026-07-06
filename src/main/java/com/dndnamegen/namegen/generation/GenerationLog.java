package com.dndnamegen.namegen.generation;

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

/**
 * Audit row for one generation attempt -- written regardless of outcome, per
 * docs/ARCHITECTURE.md's generation_log rationale. "One attempt" here means one
 * iteration of NameGenerationService's bounded retry loop (one call to the
 * model), not one outer replenishment cycle -- that granularity is what makes
 * "how often does parsing fail" answerable from this table.
 *
 * provider/model/raw_response are left null in this slice: provider/model
 * aren't exposed by the ChatClient call site used here (only one provider
 * exists until Week 4), and raw_response would require restructuring away
 * from the structured-output entity() call to capture pre-parse text. Both
 * are nullable columns; revisit when either becomes available.
 */
@Entity
@Table(name = "generation_log")
public class GenerationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Race race;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationMode mode;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "names_requested")
    private Integer namesRequested;

    @Column(name = "names_accepted")
    private Integer namesAccepted;

    @Column(name = "names_rejected_duplicate")
    private Integer namesRejectedDuplicate;

    @Column(name = "names_rejected_quality")
    private Integer namesRejectedQuality;

    @Column(name = "parse_success", nullable = false)
    private boolean parseSuccess;

    @Column(name = "error_message")
    private String errorMessage;

    protected GenerationLog() {}

    private GenerationLog(
            Race race,
            Gender gender,
            GenerationMode mode,
            String promptVersion,
            Integer namesRequested,
            Integer namesAccepted,
            Integer namesRejectedDuplicate,
            Integer namesRejectedQuality,
            boolean parseSuccess,
            String errorMessage) {
        this.ts = Instant.now();
        this.race = race;
        this.gender = gender;
        this.mode = mode;
        this.promptVersion = promptVersion;
        this.namesRequested = namesRequested;
        this.namesAccepted = namesAccepted;
        this.namesRejectedDuplicate = namesRejectedDuplicate;
        this.namesRejectedQuality = namesRejectedQuality;
        this.parseSuccess = parseSuccess;
        this.errorMessage = errorMessage;
    }

    public static GenerationLog parseFailure(
            Race race, Gender gender, GenerationMode mode, String promptVersion, int namesRequested, String errorMessage) {
        return new GenerationLog(race, gender, mode, promptVersion, namesRequested, null, null, null, false, errorMessage);
    }

    public static GenerationLog success(
            Race race,
            Gender gender,
            GenerationMode mode,
            String promptVersion,
            int namesRequested,
            int namesAccepted,
            int namesRejectedDuplicate,
            int namesRejectedQuality) {
        return new GenerationLog(
                race,
                gender,
                mode,
                promptVersion,
                namesRequested,
                namesAccepted,
                namesRejectedDuplicate,
                namesRejectedQuality,
                true,
                null);
    }

    public Long getId() {
        return id;
    }

    public Instant getTs() {
        return ts;
    }

    public Race getRace() {
        return race;
    }

    public Gender getGender() {
        return gender;
    }

    public GenerationMode getMode() {
        return mode;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Integer getNamesRequested() {
        return namesRequested;
    }

    public Integer getNamesAccepted() {
        return namesAccepted;
    }

    public Integer getNamesRejectedDuplicate() {
        return namesRejectedDuplicate;
    }

    public Integer getNamesRejectedQuality() {
        return namesRejectedQuality;
    }

    public boolean isParseSuccess() {
        return parseSuccess;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
