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
}
