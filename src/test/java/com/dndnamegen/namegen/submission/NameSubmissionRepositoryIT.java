package com.dndnamegen.namegen.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.user.User;
import com.dndnamegen.namegen.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class NameSubmissionRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private NameSubmissionRepository nameSubmissionRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        nameSubmissionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void save_should_PersistSubmission_When_FieldsAreValid() {
        User submitter = userRepository.saveAndFlush(new User("Submitter", "{bcrypt}$2a$10$submissionsubmissionsubmiss1"));

        NameSubmission saved = nameSubmissionRepository.saveAndFlush(
                new NameSubmission(submitter.getId(), "Aelar", Race.ELF, Gender.MASCULINE));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getNormalizedName()).isEqualTo("aelar");
        assertThat(saved.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<NameSubmission> found = nameSubmissionRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("Aelar");
        assertThat(found.get().getSubmitterId()).isEqualTo(submitter.getId());
        assertThat(found.get().getRace()).isEqualTo(Race.ELF);
        assertThat(found.get().getGender()).isEqualTo(Gender.MASCULINE);
    }

    @Test
    void save_should_RejectDuplicatePendingSubmission_When_NormalizedNameRaceAndGenderCollide() {
        User submitter = userRepository.saveAndFlush(new User("SubmitterTwo", "{bcrypt}$2a$10$submissionsubmissionsubmiss2"));

        nameSubmissionRepository.saveAndFlush(
                new NameSubmission(submitter.getId(), "  Aelar  ", Race.ELF, Gender.MASCULINE));

        assertThatThrownBy(() -> nameSubmissionRepository.saveAndFlush(
                        new NameSubmission(submitter.getId(), "aelar", Race.ELF, Gender.MASCULINE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_should_PersistSubmission_When_RaceIsHalfOrc() {
        // Regression: HALF_ORC is a valid Race but was missing from the migration's race CHECK,
        // so a half-orc submission failed at insert. @Enumerated(STRING) persists the literal
        // "HALF_ORC", which the CHECK must now permit.
        User submitter =
                userRepository.saveAndFlush(new User("HalfOrcSubmitter", "{bcrypt}$2a$10$submissionsubmissionsubmiss3"));

        NameSubmission saved = nameSubmissionRepository.saveAndFlush(
                new NameSubmission(submitter.getId(), "Grommash", Race.HALF_ORC, Gender.MASCULINE));

        assertThat(nameSubmissionRepository.findById(saved.getId()))
                .get()
                .extracting(NameSubmission::getRace)
                .isEqualTo(Race.HALF_ORC);
    }

    @Test
    void insert_should_AllowMultipleResolvedRows_When_TheyShareNormalizedNameRaceAndGender() {
        // The uniqueness key is a partial index scoped to WHERE status = 'PENDING', so any number
        // of resolved (APPROVED/REJECTED) rows for the same (name, race, gender) may coexist --
        // this is what lets a rejected name be resubmitted and rejected again without colliding.
        // Inserted natively because the entity constructor only ever produces PENDING rows (no
        // status setter until the moderation slice lands).
        User submitter =
                userRepository.saveAndFlush(new User("Rejecter", "{bcrypt}$2a$10$submissionsubmissionsubmiss4"));

        insertResolved(submitter.getId(), "aelar", "REJECTED");
        insertResolved(submitter.getId(), "aelar", "REJECTED");

        assertThat(nameSubmissionRepository.count()).isEqualTo(2);
    }

    /**
     * Backs the admin submissions queue (PR 4): verifies the ad-hoc join to User for
     * submitterUsername, the PENDING-only filter (a REJECTED row is excluded), and oldest-first
     * ordering -- none of which a mocked-repository service test could demonstrate.
     */
    @Test
    void findPendingSummaries_should_ReturnPendingRowsOldestFirst_When_QueuedWithResolvedRow() {
        User first = userRepository.saveAndFlush(new User("FirstSubmitter", "{bcrypt}$2a$10$submissionsubmissionsubmiss5"));
        User second = userRepository.saveAndFlush(new User("SecondSubmitter", "{bcrypt}$2a$10$submissionsubmissionsubmiss6"));
        nameSubmissionRepository.saveAndFlush(new NameSubmission(first.getId(), "Aelar", Race.ELF, Gender.MASCULINE));
        nameSubmissionRepository.saveAndFlush(
                new NameSubmission(second.getId(), "Borin", Race.DWARF, Gender.MASCULINE));
        insertResolved(first.getId(), "rejectedname", "REJECTED");

        List<PendingSubmissionSummary> summaries =
                nameSubmissionRepository.findPendingSummaries(SubmissionStatus.PENDING, PageRequest.of(0, 10));

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).getDisplayName()).isEqualTo("Aelar");
        assertThat(summaries.get(0).getSubmitterUsername()).isEqualTo("FirstSubmitter");
        assertThat(summaries.get(1).getDisplayName()).isEqualTo("Borin");
        assertThat(summaries.get(1).getSubmitterUsername()).isEqualTo("SecondSubmitter");
    }

    private void insertResolved(Long submitterId, String normalized, String status) {
        jdbcTemplate.update(
                "INSERT INTO name_submissions "
                        + "(display_name, normalized_name, race, gender, status, submitter_id, created_at) "
                        + "VALUES (?, ?, 'ELF', 'MASCULINE', ?, ?, now())",
                normalized,
                normalized,
                status,
                submitterId);
    }
}