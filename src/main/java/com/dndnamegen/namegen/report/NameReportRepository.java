package com.dndnamegen.namegen.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NameReportRepository extends JpaRepository<NameReport, Long> {

    Optional<NameReport> findByOwnerIdAndNameId(Long ownerId, Long nameId);

    /**
     * Ids only, for the browse page to mark already-reported names. Not a listing endpoint --
     * per docs/DECISIONS.md's report/ entry, there's still no product need to let an owner see
     * or retract their own reports; this stays internal to the browse-page render path.
     * Explicit @Query, not a derived findNameIdByOwnerId -- see FavoriteRepository's matching
     * method for why the derived form was found broken against this stack.
     */
    @Query("SELECT r.nameId FROM NameReport r WHERE r.ownerId = :ownerId")
    List<Long> findNameIdByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Backs the admin reports screen (slice 9): one row per reported name, with its current
     * status and total report count, worst-offenders-first. Ad-hoc {@code join Name n on n.id =
     * r.nameId} rather than a mapped {@code @ManyToOne} -- {@link NameReport} deliberately has no
     * entity association to {@code Name} (see its own Javadoc on why {@code name_id} stays a bare
     * column), and this is the only query in the codebase that needs the join, so it isn't worth
     * introducing one just here.
     */
    @Query(
            """
            SELECT n.id AS nameId, n.displayName AS displayName, n.status AS status, COUNT(r) AS reportCount
            FROM NameReport r JOIN Name n ON n.id = r.nameId
            GROUP BY n.id, n.displayName, n.status
            ORDER BY COUNT(r) DESC
            """)
    List<ReportedNameSummary> findReportedNameSummaries();

    /**
     * A handful of reasons for one reported name, most recent first, to give the admin reports
     * screen a taste of *why* without rendering every single row -- {@code Pageable} caps the
     * count without a separate LIMIT-flavored query per database.
     */
    @Query("SELECT r.reason FROM NameReport r WHERE r.nameId = :nameId AND r.reason IS NOT NULL "
            + "ORDER BY r.createdAt DESC")
    List<String> findReasonSamples(@Param("nameId") Long nameId, Pageable pageable);
}
