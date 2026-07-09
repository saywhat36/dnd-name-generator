package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NameSubmissionRepository extends JpaRepository<NameSubmission, Long> {

    /**
     * Soft duplicate check for a friendly 409 in NameSubmissionService before the insert --
     * "there's already a pending submission for this name". This is the read side of the
     * uq_submissions_pending partial index (see V7); the index itself remains the actual
     * race-safe backstop, and the service still catches its DataIntegrityViolationException for
     * the concurrent case that slips past this pre-check (mirrors NameReportService.saveNew).
     */
    boolean existsByNormalizedNameAndRaceAndGenderAndStatus(
            String normalizedName, Race race, Gender gender, SubmissionStatus status);
}