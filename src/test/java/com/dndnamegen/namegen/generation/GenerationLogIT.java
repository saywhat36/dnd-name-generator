package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies GenerationLog actually persists against the real generation_log
 * schema (column names, NOT NULL constraints, enum mapping) -- a mocked
 * repository would only prove the entity compiles, not that it saves.
 */
@Testcontainers
@SpringBootTest
class GenerationLogIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private GenerationLogRepository generationLogRepository;

    @Test
    void save_should_PersistASuccessfulAttempt() {
        GenerationLog log = GenerationLog.success(Race.ELF, Gender.FEMININE, GenerationMode.STANDARD, "v1", 5, 3, 1, 1);

        GenerationLog saved = generationLogRepository.save(log);
        GenerationLog reloaded =
                generationLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getRace()).isEqualTo(Race.ELF);
        assertThat(reloaded.getGender()).isEqualTo(Gender.FEMININE);
        assertThat(reloaded.getMode()).isEqualTo(GenerationMode.STANDARD);
        assertThat(reloaded.getPromptVersion()).isEqualTo("v1");
        assertThat(reloaded.getNamesRequested()).isEqualTo(5);
        assertThat(reloaded.getNamesAccepted()).isEqualTo(3);
        assertThat(reloaded.getNamesRejectedDuplicate()).isEqualTo(1);
        assertThat(reloaded.getNamesRejectedQuality()).isEqualTo(1);
        assertThat(reloaded.isParseSuccess()).isTrue();
        assertThat(reloaded.getErrorMessage()).isNull();
        assertThat(reloaded.getTs()).isNotNull();
    }

    @Test
    void save_should_PersistAParseFailure_With_NullYieldCounts() {
        GenerationLog log =
                GenerationLog.parseFailure(Race.DWARF, Gender.MASCULINE, GenerationMode.STANDARD, "v1", 5, "boom");

        GenerationLog saved = generationLogRepository.save(log);
        GenerationLog reloaded =
                generationLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isParseSuccess()).isFalse();
        assertThat(reloaded.getErrorMessage()).isEqualTo("boom");
        assertThat(reloaded.getNamesRequested()).isEqualTo(5);
        assertThat(reloaded.getNamesAccepted()).isNull();
        assertThat(reloaded.getNamesRejectedDuplicate()).isNull();
        assertThat(reloaded.getNamesRejectedQuality()).isNull();
    }
}
