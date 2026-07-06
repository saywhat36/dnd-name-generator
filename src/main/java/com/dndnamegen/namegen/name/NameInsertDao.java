package com.dndnamegen.namegen.name;

import com.dndnamegen.namegen.generation.DeduplicationService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the one native insert path for AI-generated names. JPA's {@code saveAll}
 * has no clean mapping for {@code ON CONFLICT DO NOTHING}, and in Postgres a
 * constraint violation marks the enclosing transaction rollback-only, so
 * "catch and continue" inside a single JPA transaction silently doesn't work --
 * see docs/ARCHITECTURE.md, "Inserts". Everything else in this codebase stays
 * on JPA/NameRepository.
 */
@Repository
public class NameInsertDao {

    private static final String INSERT_SQL =
            """
            INSERT INTO names
                (display_name, normalized_name, race, gender, source, status,
                 provider, model, prompt_version, generation_log_id, created_at)
            VALUES (?, ?, ?, ?, 'AI_GENERATED', 'ACTIVE', ?, ?, ?, ?, now())
            ON CONFLICT (normalized_name, race, gender) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public NameInsertDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts each candidate as an AI_GENERATED/ACTIVE row, one statement per
     * candidate so a conflict on one name doesn't affect the others. Returns the
     * number of candidates actually inserted -- candidates dropped by
     * {@code ON CONFLICT DO NOTHING} are not counted, which is how callers (the
     * Week 3 replenishment path) detect under-yield and decide whether to retry.
     * Candidates are expected to have already passed {@code QualityGateService}
     * and {@code DeduplicationService} -- this DAO only owns the insert itself,
     * not content validation.
     */
    public int insertGenerated(
            Race race,
            Gender gender,
            List<String> displayNames,
            String provider,
            String model,
            String promptVersion,
            Long generationLogId) {
        int insertedCount = 0;
        for (String displayName : displayNames) {
            if (displayName == null) {
                continue;
            }
            String trimmed = displayName.trim();
            insertedCount += jdbcTemplate.update(
                    INSERT_SQL,
                    trimmed,
                    DeduplicationService.normalize(trimmed),
                    race.name(),
                    gender.name(),
                    provider,
                    model,
                    promptVersion,
                    generationLogId);
        }
        return insertedCount;
    }
}
