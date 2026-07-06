package com.dndnamegen.namegen.name;

import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
            long aiPoolSize = nameRepository.countByRaceAndGenderAndStatusAndSource(
                    race, gender, NameStatus.ACTIVE, NameSource.AI_GENERATED);
            if (aiPoolSize < replenishThreshold) {
                poolReplenishmentService.replenish(race, gender);
            }
        }

        return names;
    }

    private static List<NameSource> toSources(NameSourceFilter filter) {
        return switch (filter) {
            case CURATED -> List.of(NameSource.CURATED);
            case AI_GENERATED -> List.of(NameSource.AI_GENERATED);
            case BOTH -> List.of(NameSource.CURATED, NameSource.AI_GENERATED);
        };
    }
}
