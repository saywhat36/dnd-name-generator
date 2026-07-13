package com.dndnamegen.namegen.name;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the CURATED/AI_GENERATED/USER_SUBMITTED/ALL source toggle. Name serving is a pure
 * database read on every source and never triggers AI generation -- growing a combo's AI pool
 * is exclusively a user-initiated action on the AI source (the "Generate five more" button ->
 * POST /browse/generate-more -> PoolReplenishmentService.replenish, see NameBrowserController).
 *
 * <p>Issue #98: this class used to also own the "threshold-triggered" half of replenishment,
 * auto-scheduling replenish(...) whenever a served pool that included AI_GENERATED sat below a
 * configured threshold. That fired an LLM call as a side effect of merely browsing -- including
 * from the ALL view, and immediately on any AI pool with fewer than five names, with no user
 * action. Generation is now driven only by the explicit button, so no source ever spends a
 * generation call on its own.
 */
@Service
public class NameService {

    private final NameRepository nameRepository;

    public NameService(NameRepository nameRepository) {
        this.nameRepository = nameRepository;
    }

    /**
     * Always serves from what's already in the database -- a pure read that neither blocks on nor
     * schedules a live LLM call for any source. AI pool growth is user-driven only; see the class
     * Javadoc and {@link com.dndnamegen.namegen.web.NameBrowserController#generateMore}.
     */
    public List<Name> getNames(Race race, Gender gender, NameSourceFilter sourceFilter) {
        return nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                race, gender, NameStatus.ACTIVE, toSources(sourceFilter));
    }

    /**
     * Manual review flow (docs/ROADMAP.md, Week 5): a human has decided this name should stop
     * being served, and flips it out of the ACTIVE pool. This is a direct action on a specific
     * name id, not derived from name_reports -- a report is a raw signal only (see
     * docs/ARCHITECTURE.md's "name_reports" section), and there is no threshold-based
     * auto-flagging in this codebase to wire this into. @Transactional here because
     * NameRepository.updateStatus is a @Modifying query, which Spring Data requires to run
     * inside a transaction. Returns false (caller maps to 404) if no name has this id.
     */
    @Transactional
    public boolean flagName(Long nameId) {
        return nameRepository.updateStatus(nameId, NameStatus.FLAGGED) > 0;
    }

    /** Admin reject (slice 9): same shape as {@link #flagName(Long)}, target status REJECTED. */
    @Transactional
    public boolean rejectName(Long nameId) {
        return nameRepository.updateStatus(nameId, NameStatus.REJECTED) > 0;
    }

    /**
     * Admin unflag (slice 9): reverses a FLAGGED/REJECTED status back to ACTIVE. Same
     * updateStatus call as {@link #flagName(Long)}/{@link #rejectName(Long)} -- a status is a
     * status, there's no separate "undo" column to reconcile.
     */
    @Transactional
    public boolean unflagName(Long nameId) {
        return nameRepository.updateStatus(nameId, NameStatus.ACTIVE) > 0;
    }

    private static List<NameSource> toSources(NameSourceFilter filter) {
        return switch (filter) {
            // Issue #81: USER_SUBMITTED is its own filter. CURATED means only handbook names,
            // AI_GENERATED only AI names, USER_SUBMITTED only approved user submissions, and ALL
            // is every served source -- the three above combined -- so the "All" label matches
            // what it returns.
            case CURATED -> List.of(NameSource.CURATED);
            case AI_GENERATED -> List.of(NameSource.AI_GENERATED);
            case USER_SUBMITTED -> List.of(NameSource.USER_SUBMITTED);
            case ALL -> List.of(NameSource.CURATED, NameSource.AI_GENERATED, NameSource.USER_SUBMITTED);
        };
    }
}
