package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.generation.DeduplicationService;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the one native insert path for user-submitted names. Mirrors {@code NameInsertDao}:
 * JPA's {@code saveAll} has no clean mapping for {@code ON CONFLICT DO NOTHING}, and in Postgres
 * a constraint violation marks the enclosing transaction rollback-only, so "catch and continue"
 * inside a single JPA transaction silently doesn't work. See docs/ARCHITECTURE.md, "Inserts".
 * Everything else in this codebase stays on JPA/NameRepository.
 */
@Repository
public class SubmissionInsertDao {

    private static final String INSERT_SQL =
            """
            INSERT INTO names
                (display_name, normalized_name, race, gender, source, status,
                 provider, model, prompt_version, generation_log_id, submitter_id, created_at)
            VALUES (?, ?, ?, ?, 'USER_SUBMITTED', 'ACTIVE', NULL, NULL, NULL, NULL, ?, now())
            ON CONFLICT (normalized_name, race, gender) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public SubmissionInsertDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts the user-submitted name as a USER_SUBMITTED/ACTIVE row, idempotent via
     * {@code ON CONFLICT DO NOTHING}. Returns the number actually inserted (0 or 1) -- callers
     * use this to detect whether a name already existed (insert returned 0, name already visible,
     * but submission is still marked APPROVED to record the reviewer's decision).
     *
     * <p>Provider/model/prompt_version/generation_log_id are set to NULL (not AI-generated).
     * {@code submitterId} records who proposed the name (issue #81) so the "user submitted" view
     * can show a username -- persisted even when the row already existed and this insert no-ops,
     * because the value is written on the winning insert, not this call. Caller assumes the
     * displayName has already passed validation.
     */
    public int insertSubmitted(String displayName, Race race, Gender gender, Long submitterId) {
        if (displayName == null || displayName.isBlank()) {
            return 0;
        }
        String trimmed = displayName.trim();
        return jdbcTemplate.update(
                INSERT_SQL,
                trimmed,
                DeduplicationService.normalize(trimmed),
                race.name(),
                gender.name(),
                submitterId);
    }
}
