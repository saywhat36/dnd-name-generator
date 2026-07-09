package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Backs the admin submissions queue (PR 4): one row per PENDING submission, oldest-first (so
     * the longest-waiting proposals surface first), with the submitter's username joined in for
     * display. Ad-hoc {@code JOIN User u ON u.id = s.submitterId} rather than a mapped
     * {@code @ManyToOne} -- same reasoning as {@code NameReportRepository.findReportedNameSummaries}:
     * {@link NameSubmission} deliberately has no entity association to {@code User}, and this is
     * the only query needing the join.
     *
     * <p>{@code Pageable} caps the row count -- {@code AdminSubmissionService}'s {@code MAX_PENDING}
     * bound, so the queue read stays fixed-cost no matter how large the backlog grows.
     */
    @Query(
            """
            SELECT s.id AS submissionId, s.displayName AS displayName, s.race AS race, s.gender AS gender,
                   u.username AS submitterUsername, s.createdAt AS createdAt
            FROM NameSubmission s JOIN User u ON u.id = s.submitterId
            WHERE s.status = :status
            ORDER BY s.createdAt ASC
            """)
    List<PendingSubmissionSummary> findPendingSummaries(@Param("status") SubmissionStatus status, Pageable pageable);
}