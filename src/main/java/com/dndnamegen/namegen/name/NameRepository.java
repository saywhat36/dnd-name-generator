package com.dndnamegen.namegen.name;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NameRepository extends JpaRepository<Name, Long> {

    List<Name> findByRaceAndGenderAndStatusAndSource(
            Race race, Gender gender, NameStatus status, NameSource source);

    /**
     * Backs the CURATED/AI_GENERATED/BOTH source toggle in NameService.getNames --
     * BOTH queries CURATED and AI_GENERATED together in one call rather than two
     * separate queries merged in application code.
     */
    List<Name> findByRaceAndGenderAndStatusAndSourceIn(
            Race race, Gender gender, NameStatus status, List<NameSource> sources);

    /**
     * Used by PoolReplenishmentService both to check the per-combo pool cap
     * (ACTIVE/AI_GENERATED count) and to detect a combo with no CURATED
     * examples yet (see docs/DECISIONS.md, "PoolReplenishmentService" --
     * generation is skipped rather than sent with a hollow few-shot prompt).
     */
    long countByRaceAndGenderAndStatusAndSource(
            Race race, Gender gender, NameStatus status, NameSource source);

    /**
     * Every existing normalized name for this race/gender, regardless of status or
     * source -- the (normalized_name, race, gender) unique constraint applies across
     * all rows, not just ACTIVE/CURATED ones, so dedup pre-filtering must too.
     */
    List<String> findNormalizedNameByRaceAndGender(Race race, Gender gender);
}
