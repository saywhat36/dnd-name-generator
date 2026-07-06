package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Calls the real Gemini API to confirm the ChatClient pipe works. Excluded from the
 * default `mvn test` run via the `**&#47;*EvalIT.java` Surefire exclude in pom.xml;
 * run manually with: GEMINI_API_KEY=... ./mvnw test -Dtest=NameGenerationServiceEvalIT
 */
@SpringBootTest
@ActiveProfiles("gemini")
class NameGenerationServiceEvalIT {

    @Autowired private NameGenerationService nameGenerationService;

    @BeforeEach
    void requireApiKey() {
        assumeTrue(System.getenv("GEMINI_API_KEY") != null, "GEMINI_API_KEY is not set, skipping live eval test");
    }

    @Test
    void testPrompt_should_ReturnNonBlankResponse_When_CalledAgainstRealProvider() {
        String response = nameGenerationService.testPrompt("Say hello in one short sentence.");

        assertThat(response).isNotBlank();
    }
}
