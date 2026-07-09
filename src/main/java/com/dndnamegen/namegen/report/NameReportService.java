package com.dndnamegen.namegen.report;

import com.dndnamegen.namegen.identity.Identity;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Records a raw report signal, keyed on {@link Identity#ownerId()} -- a report is not an
 * automatic status change; flagging to FLAGGED is a separate, not-yet-built action.
 *
 * <p>Reports go owner-keyed here (see docs/DECISIONS.md, slice 6) now that every request
 * resolving an {@code Identity} is authenticated and always carries a non-null {@code ownerId} --
 * the earlier session-keyed design (see the superseded slice-5 decision log entry) existed only
 * because {@code name_reports} had no {@code owner_id} column yet, not because session-keying was
 * preferred. Mirrors {@code FavoriteService}'s shape exactly.
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
     * docs/ARCHITECTURE.md's rationale for the (owner_id, name_id) unique constraint).
     * Returns the existing row if this owner already reported this name, ignoring the new
     * reason -- matches FavoriteService.addFavorite's idempotent-add pattern, including the
     * same catch-and-reread handling for a concurrent-report race off the unique constraint.
     */
    public NameReport reportName(Identity identity, Long nameId, String reason) {
        Long ownerId = identity.ownerId();
        return nameReportRepository
                .findByOwnerIdAndNameId(ownerId, nameId)
                .orElseGet(() -> saveNew(ownerId, nameId, reason));
    }

    private NameReport saveNew(Long ownerId, Long nameId, String reason) {
        try {
            return nameReportRepository.save(new NameReport(ownerId, nameId, reason));
        } catch (DataIntegrityViolationException e) {
            return nameReportRepository
                    .findByOwnerIdAndNameId(ownerId, nameId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Used by the browse page to mark already-reported names on initial render. Matches
     * FavoriteService.getFavoritedNameIds's shape -- membership only, no ordering needed.
     */
    public Set<Long> getReportedNameIds(Identity identity) {
        return Set.copyOf(nameReportRepository.findNameIdByOwnerId(identity.ownerId()));
    }
}
