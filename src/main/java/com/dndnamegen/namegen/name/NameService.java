package com.dndnamegen.namegen.name;

import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the CURATED/AI_GENERATED/BOTH source toggle and the
 * threshold-triggered half of pool replenishment described in
 * docs/ARCHITECTURE.md's "Request flow" section: this class decides
 * *when* to call PoolReplenishmentService.replenish(...); the service itself
 * (stampede guard, budget/cap checks, generation, insert) is unchanged from
 * the previous Week 3 slice.
 */
@Service
public class NameService {

    private final NameRepository nameRepository;
    private final PoolReplenishmentService poolReplenishmentService;
    private final int replenishThreshold;

    public NameService(
            NameRepository nameRepository,
            PoolReplenishmentService poolReplenishmentService,
            @Value("${app.pool-replenishment.replenish-threshold:5}") int replenishThreshold) {
        this.nameRepository = nameRepository;
        this.poolReplenishmentService = poolReplenishmentService;
        this.replenishThreshold = replenishThreshold;
    }

    /**
     * Always serves from what's already in the database (never blocks on a live LLM
     * call). If the requested source includes AI_GENERATED and that combo's AI pool is
     * below the configured threshold, triggers PoolReplenishmentService.replenish(...) --
     * an @Async call on a different bean, so this method returns as soon as it's
     * scheduled, not when it completes.
     */
    public List<Name> getNames(Race race, Gender gender, NameSourceFilter sourceFilter) {
        List<NameSource> sources = toSources(sourceFilter);
        List<Name> names =
                nameRepository.findByRaceAndGenderAndStatusAndSourceIn(race, gender, NameStatus.ACTIVE, sources);

        if (sources.contains(NameSource.AI_GENERATED)) {
            // Derived from the list just fetched above rather than a second DB round trip --
            // that list already contains every ACTIVE/AI_GENERATED row for this combo, since
            // AI_GENERATED is always one of the requested sources whenever this branch runs.
            long aiPoolSize =
                    names.stream().filter(name -> name.getSource() == NameSource.AI_GENERATED).count();
            if (aiPoolSize < replenishThreshold) {
                poolReplenishmentService.replenish(race, gender);
            }
        }

        return names;
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
            case CURATED -> List.of(NameSource.CURATED, NameSource.USER_SUBMITTED);
            case AI_GENERATED -> List.of(NameSource.AI_GENERATED);
            case BOTH -> List.of(NameSource.CURATED, NameSource.AI_GENERATED, NameSource.USER_SUBMITTED);
        };
    }
}
