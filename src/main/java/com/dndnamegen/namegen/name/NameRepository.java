package com.dndnamegen.namegen.name;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Bulk JPQL update rather than a JPA save() -- Name has no setters and is otherwise
     * read-only via JPA (pool writes go through NameInsertDao's native path instead, for the
     * unrelated ON CONFLICT/batch-poisoning reason documented there). A single-row status flip
     * has neither of those problems, so a plain @Modifying query is the simplest correct tool
     * here rather than adding a setter for one field. The WHERE clause matches on id alone
     * (not status), so re-flagging an already-FLAGGED row is naturally idempotent -- it still
     * matches and returns 1, just with no observable change.
     *
     * clearAutomatically = true: no caller today loads a Name entity into the persistence
     * context before calling this in the same transaction, but without this flag a future
     * caller that did (e.g. findById(id) before updateStatus(id, ...), in the same
     * @Transactional method) would keep reading the pre-update status from Hibernate's
     * first-level cache instead of what was actually just written -- a bulk JPQL update
     * bypasses the persistence context entirely, so it doesn't invalidate it on its own.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Name n SET n.status = :status WHERE n.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") NameStatus status);
}
