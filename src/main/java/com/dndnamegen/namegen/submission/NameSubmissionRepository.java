package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * Backs "my submissions" (owner-keyed, GET /submissions/mine): every submission this user
     * has made, regardless of status, most-recent-first. Mirrors {@code
     * FavoriteRepository.findByOwnerIdOrderByCreatedAtDescIdDesc} exactly, including its
     * {@code id} tiebreaker -- two submissions in the same request can land on the same
     * {@code Instant} (client-side {@code createdAt}, see {@code NameSubmission}'s constructor),
     * and {@code createdAt} alone gives no stable order between them.
     */
    List<NameSubmission> findBySubmitterIdOrderByCreatedAtDescIdDesc(Long submitterId);

    /**
     * Backs the admin submissions queue (PR 4, paginated as of the follow-up in issue #86): one
     * row per PENDING submission, oldest-first (so the longest-waiting proposals surface first),
     * with the submitter's username joined in for display. Ad-hoc
     * {@code JOIN User u ON u.id = s.submitterId} rather than a mapped {@code @ManyToOne} -- same
     * reasoning as {@code NameReportRepository.findReportedNameSummaries}: {@link NameSubmission}
     * deliberately has no entity association to {@code User}, and this is the only query needing
     * the join.
     *
     * <p>Returns a {@code Page}, not a bare {@code List}, so the admin screen can render
     * previous/next navigation and a total count once the queue outgrows one page -- unlike the
     * count query {@code NameService.getNames} deliberately avoided (see docs/DECISIONS.md,
     * "Avoid redundant COUNT query"), there is no already-fetched list here to derive a total
     * from, so this COUNT is the only way to get one, not a redundant one. An explicit
     * {@code countQuery} is supplied because Spring Data's auto-derivation doesn't reliably
     * strip the {@code JOIN}/column list from a query this shape.
     *
     * <p>Ordered by {@code createdAt} then {@code id} -- {@code createdAt} alone has no unique
     * tiebreaker, so two submissions landing in the same instant (plausible: both are
     * {@code Instant.now()} at construction, not DB-generated) would order nondeterministically,
     * making page boundaries and any ordering-sensitive test flaky. {@code id} is monotonically
     * increasing (BIGSERIAL), so it's a correct, stable secondary key.
     */
    @Query(
            value =
                    """
                    SELECT s.id AS submissionId, s.displayName AS displayName, s.race AS race, s.gender AS gender,
                           u.username AS submitterUsername, s.createdAt AS createdAt
                    FROM NameSubmission s JOIN User u ON u.id = s.submitterId
                    WHERE s.status = :status
                    ORDER BY s.createdAt ASC, s.id ASC
                    """,
            countQuery =
                    """
                    SELECT COUNT(s) FROM NameSubmission s WHERE s.status = :status
                    """)
    Page<PendingSubmissionSummary> findPendingSummaries(@Param("status") SubmissionStatus status, Pageable pageable);

    /**
     * Admin approve/reject: bulk update the submission's status, reviewer, and reviewed timestamp.
     * {@code @Modifying(clearAutomatically = true)} for the same reason as {@code NameRepository.updateStatus}:
     * bulk JPA updates don't trigger setter chains (the entity is setter-free), and the first-level
     * cache must be cleared so future reads see the new state, not the stale in-memory version.
     *
     * <p>Returns the number of rows updated (0 if the submission doesn't exist).
     */
    @Modifying(clearAutomatically = true)
    @Query(
            """
            UPDATE NameSubmission s
            SET s.status = :status, s.reviewerId = :reviewerId, s.reviewedAt = :reviewedAt
            WHERE s.id = :id
            """)
    int updateStatus(
            @Param("id") Long id,
            @Param("status") SubmissionStatus status,
            @Param("reviewerId") Long reviewerId,
            @Param("reviewedAt") Instant reviewedAt);
}