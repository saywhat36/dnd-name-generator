package com.dndnamegen.namegen.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * Regression test for https://github.com/saywhat36/dnd-name-generator/issues/46:
 * findNormalizedNameByRaceAndGender was originally a derived Spring Data method declared to
 * return List<String>, but compiled to a full-entity SELECT against Name on this Hibernate/
 * Spring Data stack, throwing QueryTypeMismatchException the moment a real replenishment cycle
 * reached it in production. A mocked NameRepository (as DeduplicationServiceTest uses) cannot
 * catch this -- it only surfaces when a real JPA provider actually builds and runs the query.
 */
@Testcontainers
@SpringBootTest
class NameRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private NameRepository nameRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM names WHERE provider = 'test-provider'");
    }

    /**
     * DRAGONBORN has the race CHECK constraint but no curated seed data (per docs/DECISIONS.md's
     * V3 seed entry), so this combo starts empty regardless of the V1-V3 Flyway migrations that
     * run automatically against this Testcontainers datasource -- unlike e.g. ELF/FEMININE, which
     * already has hundreds of curated rows and would make an exact-match assertion fragile.
     */
    @Test
    void findNormalizedNameByRaceAndGender_should_ReturnNormalizedNames_When_RowsExistForThisCombo() {
        jdbcTemplate.update(
                "INSERT INTO names (display_name, normalized_name, race, gender, source, status, provider) "
                        + "VALUES ('Sylvaine', 'sylvaine', 'DRAGONBORN', 'FEMININE', 'AI_GENERATED', 'ACTIVE', 'test-provider'), "
                        + "('Nymrienne', 'nymrienne', 'DRAGONBORN', 'FEMININE', 'AI_GENERATED', 'ACTIVE', 'test-provider')");

        List<String> result = nameRepository.findNormalizedNameByRaceAndGender(Race.DRAGONBORN, Gender.FEMININE);

        assertThat(result).containsExactlyInAnyOrder("sylvaine", "nymrienne");
    }

    @Test
    void findNormalizedNameByRaceAndGender_should_ReturnEmptyList_When_NoRowsExistForThisCombo() {
        List<String> result = nameRepository.findNormalizedNameByRaceAndGender(Race.DRAGONBORN, Gender.MASCULINE);

        assertThat(result).isEmpty();
    }

    @Test
    void findNormalizedNameByRaceAndGender_should_IncludeRowsRegardlessOfStatusOrSource() {
        jdbcTemplate.update(
                "INSERT INTO names (display_name, normalized_name, race, gender, source, status, provider) "
                        + "VALUES ('Flaggedina', 'flaggedina', 'DRAGONBORN', 'FEMININE', 'AI_GENERATED', 'FLAGGED', 'test-provider')");

        List<String> result = nameRepository.findNormalizedNameByRaceAndGender(Race.DRAGONBORN, Gender.FEMININE);

        assertThat(result).containsExactly("flaggedina");
    }
}
