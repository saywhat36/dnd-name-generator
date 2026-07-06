package com.dndnamegen.namegen.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the real {@code ON CONFLICT DO NOTHING} behavior against Postgres --
 * a mocked JdbcTemplate would only prove the right SQL text was sent, not that
 * conflicting inserts are actually skipped, which is the entire point of this DAO.
 */
@Testcontainers
@SpringBootTest
class NameInsertDaoIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private NameInsertDao nameInsertDao;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM names WHERE source = 'AI_GENERATED' AND provider = 'test-provider'");
    }

    @Test
    void insertGenerated_should_InsertAllCandidates_When_NoneCollide() {
        int insertedCount = nameInsertDao.insertGenerated(
                Race.ELF,
                Gender.FEMININE,
                List.of("Sylvaine", "Nymrienne"),
                "test-provider",
                "test-model",
                "v1",
                null);

        assertThat(insertedCount).isEqualTo(2);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT display_name, normalized_name, race, gender, source, status, provider, model, prompt_version "
                        + "FROM names WHERE provider = 'test-provider' ORDER BY display_name");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("display_name", "Nymrienne")
                .containsEntry("normalized_name", "nymrienne")
                .containsEntry("race", "ELF")
                .containsEntry("gender", "FEMININE")
                .containsEntry("source", "AI_GENERATED")
                .containsEntry("status", "ACTIVE")
                .containsEntry("model", "test-model")
                .containsEntry("prompt_version", "v1");
    }

    @Test
    void insertGenerated_should_SkipCandidate_When_ItCollidesWithAnExistingRow() {
        jdbcTemplate.update(
                "INSERT INTO names (display_name, normalized_name, race, gender, source, provider) "
                        + "VALUES ('Sylvaine', 'sylvaine', 'ELF', 'FEMININE', 'AI_GENERATED', 'test-provider')");

        int insertedCount = nameInsertDao.insertGenerated(
                Race.ELF,
                Gender.FEMININE,
                List.of("Sylvaine", "Nymrienne"),
                "test-provider",
                "test-model",
                "v1",
                null);

        assertThat(insertedCount).isEqualTo(1);
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM names WHERE provider = 'test-provider'", Integer.class);
        assertThat(totalRows).isEqualTo(2);
    }

    @Test
    void insertGenerated_should_SkipCandidate_When_ItCollidesOnlyAfterNormalization() {
        int insertedCount = nameInsertDao.insertGenerated(
                Race.ELF, Gender.FEMININE, List.of("  SYLVAINE  ", "Sylvaine"), "test-provider", "test-model", "v1", null);

        assertThat(insertedCount).isEqualTo(1);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT display_name FROM names WHERE provider = 'test-provider'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("display_name", "SYLVAINE");
    }

    @Test
    void insertGenerated_should_SkipNullCandidate_When_ListContainsOne() {
        int insertedCount = nameInsertDao.insertGenerated(
                Race.ELF,
                Gender.FEMININE,
                Arrays.asList("Sylvaine", null, "Nymrienne"),
                "test-provider",
                "test-model",
                "v1",
                null);

        assertThat(insertedCount).isEqualTo(2);
    }
}
