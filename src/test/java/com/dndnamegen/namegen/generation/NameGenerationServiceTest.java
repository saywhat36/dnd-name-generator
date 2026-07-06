package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.name.dto.NameSuggestion;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;

class NameGenerationServiceTest {

    private static final String TEMPLATE = "Generate {count} {race} names ({gender}). Examples: {examples}";

    private final ChatClient chatClient = mock(ChatClient.class);
    private final NameRepository nameRepository = mock(NameRepository.class);
    private final QualityGateService qualityGateService = mock(QualityGateService.class);
    private final DeduplicationService deduplicationService = mock(DeduplicationService.class);
    private final GenerationLogRepository generationLogRepository = mock(GenerationLogRepository.class);
    private final PromptTemplate nameGenerationPromptTemplate = new PromptTemplate(TEMPLATE);
    private final NameGenerationService nameGenerationService = new NameGenerationService(
            chatClient,
            nameGenerationPromptTemplate,
            nameRepository,
            qualityGateService,
            deduplicationService,
            generationLogRepository,
            3);

    @Test
    void generateNameSuggestions_should_ReturnParsedSuggestions_When_StructuredOutputSucceeds() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        List<NameSuggestion> expected = List.of(new NameSuggestion("Aelric"), new NameSuggestion("Sylvaine"));

        when(chatClient.prompt("generate elf feminine names")).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(any(ParameterizedTypeReference.class))).thenReturn(expected);

        List<NameSuggestion> result = nameGenerationService.generateNameSuggestions("generate elf feminine names");

        assertThat(result).containsExactlyElementsOf(expected);

        ArgumentCaptor<ParameterizedTypeReference> typeCaptor = ArgumentCaptor.forClass(ParameterizedTypeReference.class);
        verify(responseSpec).entity(typeCaptor.capture());
        ParameterizedType capturedType = (ParameterizedType) typeCaptor.getValue().getType();
        assertThat(capturedType.getRawType()).isEqualTo(List.class);
        assertThat(capturedType.getActualTypeArguments()).containsExactly(NameSuggestion.class);
    }

    @Test
    void generateNameSuggestions_should_BuildPromptFromCuratedExamplesOnly_When_CalledWithRaceAndGender() {
        Name curatedOne = mock(Name.class);
        Name curatedTwo = mock(Name.class);
        when(curatedOne.getDisplayName()).thenReturn("Aelric");
        when(curatedTwo.getDisplayName()).thenReturn("Sylvaine");
        when(nameRepository.findByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.CURATED))
                .thenReturn(List.of(curatedOne, curatedTwo));

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        String expectedPrompt = "Generate 5 ELF names (FEMININE). Examples: Aelric, Sylvaine";
        when(chatClient.prompt(expectedPrompt)).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(new NameSuggestion("Nymrienne")));

        List<NameSuggestion> result = nameGenerationService.generateNameSuggestions(Race.ELF, Gender.FEMININE, 5);

        assertThat(result).containsExactly(new NameSuggestion("Nymrienne"));
        verify(nameRepository)
                .findByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.CURATED);
        verify(chatClient).prompt(expectedPrompt);
    }

    @Test
    void nameGenerationPromptV1Resource_should_RenderAllPlaceholders_When_Loaded() {
        PromptTemplate realTemplate =
                PromptTemplate.builder().resource(new ClassPathResource("prompts/name-generation-v1.st")).build();

        String rendered = realTemplate.render(
                Map.of("race", "ELF", "gender", "FEMININE", "count", 5, "examples", "Aelric, Sylvaine"));

        assertThat(rendered)
                .contains("5")
                .contains("ELF")
                .contains("FEMININE")
                .contains("Aelric, Sylvaine")
                .doesNotContain("{")
                .doesNotContain("}");
    }

    /** Stubs the chatClient chain so any rendered prompt yields the given suggestions in sequence. */
    private void stubGenerationAttempts(List<List<NameSuggestion>> attemptResults) {
        when(nameRepository.findByRaceAndGenderAndStatusAndSource(any(), any(), any(), any()))
                .thenReturn(List.of());
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        var stub = when(responseSpec.entity(any(ParameterizedTypeReference.class)));
        for (List<NameSuggestion> attemptResult : attemptResults) {
            stub = stub.thenReturn(attemptResult);
        }
    }

    @Test
    void generateValidatedNames_should_RetryGeneration_When_FirstAttemptFailsToParse() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(nameRepository.findByRaceAndGenderAndStatusAndSource(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(any(ParameterizedTypeReference.class)))
                .thenThrow(new IllegalStateException("structured output parse failure"))
                .thenReturn(List.of(new NameSuggestion("Aelric")));
        when(qualityGateService.passesQualityGate("Aelric")).thenReturn(true);
        when(deduplicationService.filterDuplicates(eq(Race.ELF), eq(Gender.FEMININE), any()))
                .thenReturn(List.of("Aelric"));

        List<String> result = nameGenerationService.generateValidatedNames(Race.ELF, Gender.FEMININE, 1);

        assertThat(result).containsExactly("Aelric");
        verify(responseSpec, times(2)).entity(any(ParameterizedTypeReference.class));

        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository, times(2)).save(logCaptor.capture());
        List<GenerationLog> savedLogs = logCaptor.getAllValues();
        assertThat(savedLogs.get(0).isParseSuccess()).isFalse();
        assertThat(savedLogs.get(0).getErrorMessage()).isEqualTo("structured output parse failure");
        assertThat(savedLogs.get(0).getNamesAccepted()).isNull();
        assertThat(savedLogs.get(1).isParseSuccess()).isTrue();
        assertThat(savedLogs.get(1).getNamesRequested()).isEqualTo(1);
        assertThat(savedLogs.get(1).getNamesAccepted()).isEqualTo(1);
        assertThat(savedLogs.get(1).getNamesRejectedDuplicate()).isEqualTo(0);
        assertThat(savedLogs.get(1).getNamesRejectedQuality()).isEqualTo(0);
    }

    @Test
    void generateValidatedNames_should_RetryForShortfall_When_QualityGateRejectsSomeCandidates() {
        stubGenerationAttempts(List.of(
                List.of(new NameSuggestion("Aelric"), new NameSuggestion("Frodo")),
                List.of(new NameSuggestion("Sylvaine"))));
        when(qualityGateService.passesQualityGate("Aelric")).thenReturn(true);
        when(qualityGateService.passesQualityGate("Frodo")).thenReturn(false);
        when(qualityGateService.passesQualityGate("Sylvaine")).thenReturn(true);
        when(deduplicationService.filterDuplicates(eq(Race.ELF), eq(Gender.FEMININE), eq(List.of("Aelric"))))
                .thenReturn(List.of("Aelric"));
        when(deduplicationService.filterDuplicates(eq(Race.ELF), eq(Gender.FEMININE), eq(List.of("Aelric", "Sylvaine"))))
                .thenReturn(List.of("Aelric", "Sylvaine"));

        List<String> result = nameGenerationService.generateValidatedNames(Race.ELF, Gender.FEMININE, 2);

        assertThat(result).containsExactly("Aelric", "Sylvaine");

        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository, times(2)).save(logCaptor.capture());
        GenerationLog firstAttemptLog = logCaptor.getAllValues().get(0);
        assertThat(firstAttemptLog.getNamesRequested()).isEqualTo(2);
        assertThat(firstAttemptLog.getNamesAccepted()).isEqualTo(1);
        assertThat(firstAttemptLog.getNamesRejectedQuality()).isEqualTo(1);
        assertThat(firstAttemptLog.getNamesRejectedDuplicate()).isEqualTo(0);
    }

    @Test
    void generateValidatedNames_should_ReturnPartialResult_When_MaxAttemptsExhaustedBeforeReachingCount() {
        stubGenerationAttempts(List.of(
                List.of(new NameSuggestion("Frodo")), List.of(new NameSuggestion("Frodo")), List.of(new NameSuggestion(
                        "Frodo"))));
        when(qualityGateService.passesQualityGate("Frodo")).thenReturn(false);
        when(deduplicationService.filterDuplicates(any(), any(), any())).thenReturn(List.of());

        List<String> result = nameGenerationService.generateValidatedNames(Race.ELF, Gender.FEMININE, 3);

        assertThat(result).isEmpty();
        verify(chatClient, times(3)).prompt(anyString());
        verify(generationLogRepository, times(3)).save(any(GenerationLog.class));
    }

    @Test
    void generateValidatedNames_should_CapResult_When_MoreSurvivorsThanRequestedCount() {
        stubGenerationAttempts(List.of(List.of(
                new NameSuggestion("Aelric"), new NameSuggestion("Sylvaine"), new NameSuggestion("Nymrienne"))));
        when(qualityGateService.passesQualityGate(any())).thenReturn(true);
        when(deduplicationService.filterDuplicates(any(), any(), any()))
                .thenReturn(List.of("Aelric", "Sylvaine", "Nymrienne"));

        List<String> result = nameGenerationService.generateValidatedNames(Race.ELF, Gender.FEMININE, 2);

        assertThat(result).containsExactly("Aelric", "Sylvaine");
    }
}
