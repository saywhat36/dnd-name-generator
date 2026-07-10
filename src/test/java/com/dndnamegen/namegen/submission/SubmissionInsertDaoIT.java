package com.dndnamegen.namegen.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Exercises the real {@code ON CONFLICT DO NOTHING} behavior for user-submitted names --
 * mirrors {@code NameInsertDaoIT}'s rationale, now for {@code SubmissionInsertDao}.
 */
@Testcontainers
@SpringBootTest
class SubmissionInsertDaoIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private SubmissionInsertDao submissionInsertDao;

    @Autowired private JdbcTemplate jdbcTemplate;

    // A real submitter to satisfy the names.submitter_id FK added in V10 (issue #81).
    private Long submitterId;

    @BeforeEach
    void createSubmitter() {
        submitterId = jdbcTemplate.queryForObject(
                "INSERT INTO users (username, username_norm, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "GrishSubmitter",
                "grishsubmitter",
                "{bcrypt}$2a$10$submissioninsertdaoittestuserxxxxxxxxxxxxxxxxxxxxxx");
    }

    @AfterEach
    void cleanUp() {
        // Names first, then the submitter they reference (FK order).
        jdbcTemplate.update("DELETE FROM names WHERE normalized_name = 'grishnakh'");
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", submitterId);
    }

    @Test
    void insertSubmitted_should_InsertNewName_When_NoCollision() {
        int insertedCount =
                submissionInsertDao.insertSubmitted("Grishnakh", Race.HALF_ORC, Gender.MASCULINE, submitterId);

        assertThat(insertedCount).isEqualTo(1);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT display_name, normalized_name, race, gender, source, status, submitter_id "
                        + "FROM names WHERE source = 'USER_SUBMITTED'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("display_name", "Grishnakh")
                .containsEntry("normalized_name", "grishnakh")
                .containsEntry("race", "HALF_ORC")
                .containsEntry("gender", "MASCULINE")
                .containsEntry("source", "USER_SUBMITTED")
                .containsEntry("status", "ACTIVE")
                // Issue #81: the submitter is carried onto the name row for the "user submitted" view.
                .containsEntry("submitter_id", submitterId);
    }

    @Test
    void insertSubmitted_should_ReturnZero_When_NameAlreadyExists() {
        // Seed an existing name (any source, any status)
        jdbcTemplate.update(
                "INSERT INTO names (display_name, normalized_name, race, gender, source) "
                        + "VALUES ('Grishnakh', 'grishnakh', 'HALF_ORC', 'MASCULINE', 'CURATED')");

        // Try to insert the same name as USER_SUBMITTED -- should collide and return 0
        int insertedCount =
                submissionInsertDao.insertSubmitted("Grishnakh", Race.HALF_ORC, Gender.MASCULINE, submitterId);

        assertThat(insertedCount).isEqualTo(0);
        // Verify the original row is unchanged
        Integer curatedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM names WHERE source = 'CURATED' AND normalized_name = 'grishnakh'",
                Integer.class);
        assertThat(curatedCount).isEqualTo(1);
    }
}
