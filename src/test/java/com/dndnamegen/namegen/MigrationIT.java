package com.dndnamegen.namegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Flyway migrations apply cleanly against a real Postgres and that
 * seed data lands as expected. No test in this suite requires a live LLM key.
 */
@Testcontainers
@SpringBootTest
class MigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void migrations_should_CreateSeedData_When_ApplicationStarts() {
        Integer curatedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM names WHERE source = 'CURATED'", Integer.class);

        assertThat(curatedCount).isPositive();
    }

    @Test
    void names_should_RejectDuplicateNormalizedNameRaceAndGender() {
        String normalizedName = "test duplicate guard";
        jdbcTemplate.update(
                "INSERT INTO names (display_name, normalized_name, race, gender, source) "
                        + "VALUES (?, ?, 'ELF', 'MASCULINE', 'AI_GENERATED')",
                "Test Duplicate Guard", normalizedName);

        try {
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO names (display_name, normalized_name, race, gender, source) "
                                    + "VALUES (?, ?, 'ELF', 'MASCULINE', 'AI_GENERATED')",
                            "Test Duplicate Guard Two", normalizedName))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM names WHERE normalized_name = ?", normalizedName);
        }
    }

    @Test
    void generationLog_should_RejectInvalidRace_JustLikeNames() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO generation_log
                            (race, gender, mode, parse_success)
                        VALUES ('NOT_A_REAL_RACE', 'FEMININE', 'STANDARD', true)
                        """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void favoritesAndNameReports_should_HaveADedicatedIndexOnNameId() {
        Integer favoritesIndexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'favorites' AND indexname = 'idx_favorites_name_id'",
                Integer.class);
        Integer nameReportsIndexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'name_reports' AND indexname = 'idx_name_reports_name_id'",
                Integer.class);

        assertThat(favoritesIndexCount).isEqualTo(1);
        assertThat(nameReportsIndexCount).isEqualTo(1);
    }

    /**
     * V6 name_reports checks: session_id is nullable now (an owner-only row must insert
     * cleanly), uq_name_reports_owner_name fires on a duplicate (owner_id, name_id) pair, and
     * chk_name_reports_reporter_present still rejects a row with neither identifier set.
     */
    @Test
    void nameReports_should_AllowOwnerOnlyRow_And_RejectDuplicateOwnerNamePair_And_RejectRowWithNeitherIdentifier() {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (username, username_norm, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class, "V6TestUser", "v6testuser", "{bcrypt}$2a$10$v6v6v6v6v6v6v6v6v6v6v6");
        Long nameId = jdbcTemplate.queryForObject(
                "INSERT INTO names (display_name, normalized_name, race, gender, source) "
                        + "VALUES (?, ?, 'ELF', 'MASCULINE', 'AI_GENERATED') RETURNING id",
                Long.class, "V6 Test Name", "v6 test name");

        try {
            // session_id nullable + owner_id-only row inserts cleanly.
            jdbcTemplate.update(
                    "INSERT INTO name_reports (name_id, owner_id) VALUES (?, ?)", nameId, userId);

            // uq_name_reports_owner_name fires on a second row for the same (owner_id, name_id).
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO name_reports (name_id, owner_id) VALUES (?, ?)", nameId, userId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // chk_name_reports_reporter_present still rejects a row with neither identifier.
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO name_reports (name_id, owner_id, session_id) VALUES (?, NULL, NULL)",
                            nameId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM name_reports WHERE name_id = ?", nameId);
            jdbcTemplate.update("DELETE FROM names WHERE id = ?", nameId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void names_should_AcceptUserSubmittedSource_And_FilterCorrectly() {
        String normalizedName = "user submitted test";
        Long userSubmittedNameId = jdbcTemplate.queryForObject(
                "INSERT INTO names (display_name, normalized_name, race, gender, source, status) "
                        + "VALUES (?, ?, 'ELF', 'MASCULINE', 'USER_SUBMITTED', 'ACTIVE') RETURNING id",
                Long.class, "User Submitted Test", normalizedName);

        try {
            // USER_SUBMITTED rows appear when querying for CURATED source
            Integer countInCurated = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM names WHERE source = 'USER_SUBMITTED'",
                    Integer.class);
            assertThat(countInCurated).isEqualTo(1);

            // Verify it's distinct from AI_GENERATED
            Integer countAI = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM names WHERE source = 'AI_GENERATED' AND normalized_name = ?",
                    Integer.class, normalizedName);
            assertThat(countAI).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM names WHERE id = ?", userSubmittedNameId);
        }
    }

    /**
     * V10 (issue #81): names carry a nullable submitter_id FK -> users so the "user submitted"
     * view can attribute an approved name to a username. Verifies the dedicated index exists, that
     * a valid submitter is stored, and that the FK rejects a submitter id with no matching user.
     */
    @Test
    void names_should_HaveSubmitterFkColumnAndIndex_ForUserSubmittedAttribution() {
        Integer submitterIndexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'names' AND indexname = 'idx_names_submitter'",
                Integer.class);
        assertThat(submitterIndexCount).isEqualTo(1);

        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (username, username_norm, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class, "V10TestUser", "v10testuser", "{bcrypt}$2a$10$v10v10v10v10v10v10v10v");
        Long nameId = jdbcTemplate.queryForObject(
                "INSERT INTO names (display_name, normalized_name, race, gender, source, submitter_id) "
                        + "VALUES (?, ?, 'ELF', 'MASCULINE', 'USER_SUBMITTED', ?) RETURNING id",
                Long.class, "V10 Test Name", "v10 test name", userId);

        try {
            Long storedSubmitter = jdbcTemplate.queryForObject(
                    "SELECT submitter_id FROM names WHERE id = ?", Long.class, nameId);
            assertThat(storedSubmitter).isEqualTo(userId);

            // FK rejects a submitter id that references no user.
            assertThatThrownBy(() -> jdbcTemplate.update(
                            "INSERT INTO names (display_name, normalized_name, race, gender, source, submitter_id) "
                                    + "VALUES ('V10 Orphan', 'v10 orphan', 'ELF', 'MASCULINE', 'USER_SUBMITTED', ?)",
                            Long.MAX_VALUE))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM names WHERE id = ?", nameId);
            jdbcTemplate.update("DELETE FROM names WHERE normalized_name = 'v10 orphan'");
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
