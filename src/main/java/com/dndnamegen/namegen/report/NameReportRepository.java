package com.dndnamegen.namegen.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NameReportRepository extends JpaRepository<NameReport, Long> {

    Optional<NameReport> findBySessionIdAndNameId(String sessionId, Long nameId);

    /**
     * Ids only, for the browse page to mark already-reported names. Not a listing endpoint --
     * per docs/DECISIONS.md's report/ entry, there's still no product need to let a session
     * see or retract its own reports; this stays internal to the browse-page render path.
     * Explicit @Query, not a derived findNameIdBySessionId -- see FavoriteRepository's
     * matching method for why the derived form was found broken against this stack.
     */
    @Query("SELECT r.nameId FROM NameReport r WHERE r.sessionId = :sessionId")
    List<Long> findNameIdBySessionId(@Param("sessionId") String sessionId);
}
