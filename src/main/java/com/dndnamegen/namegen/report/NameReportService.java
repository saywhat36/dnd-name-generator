package com.dndnamegen.namegen.report;

import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Records a raw report signal, keyed on session_id, per docs/ARCHITECTURE.md -- a report is
 * not an automatic status change; flagging to FLAGGED is a separate, not-yet-built action.
 */
@Service
public class NameReportService {

    private final NameReportRepository nameReportRepository;

    public NameReportService(NameReportRepository nameReportRepository) {
        this.nameReportRepository = nameReportRepository;
    }

    /**
     * Idempotent: without this, one user clicking report five times looks like five
     * reports, which would corrupt any future threshold-triggered auto-flagging (see
     * docs/ARCHITECTURE.md's rationale for the (session_id, name_id) unique constraint).
     * Returns the existing row if this session already reported this name, ignoring the new
     * reason -- matches FavoriteService.addFavorite's idempotent-add pattern, including the
     * same catch-and-reread handling for a concurrent-report race off the unique constraint.
     */
    public NameReport reportName(String sessionId, Long nameId, String reason) {
        return nameReportRepository
                .findBySessionIdAndNameId(sessionId, nameId)
                .orElseGet(() -> saveNew(sessionId, nameId, reason));
    }

    private NameReport saveNew(String sessionId, Long nameId, String reason) {
        try {
            return nameReportRepository.save(new NameReport(sessionId, nameId, reason));
        } catch (DataIntegrityViolationException e) {
            return nameReportRepository
                    .findBySessionIdAndNameId(sessionId, nameId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Used by the browse page to mark already-reported names on initial render. Matches
     * FavoriteService.getFavoritedNameIds's shape -- membership only, no ordering needed.
     */
    public Set<Long> getReportedNameIds(String sessionId) {
        return Set.copyOf(nameReportRepository.findNameIdBySessionId(sessionId));
    }
}
