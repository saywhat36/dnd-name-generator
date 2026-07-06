package com.dndnamegen.namegen.generation;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.name.dto.NameSuggestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Week 1 proved the ChatClient pipe works end to end (plain text, no prompt
 * template, no structured output) via testPrompt. Week 2 adds the structured
 * output path used by the real generation flow, built from the externalized
 * few-shot template with examples drawn from CURATED rows only.
 */
@Service
public class NameGenerationService {

    private static final Logger log = LoggerFactory.getLogger(NameGenerationService.class);

    private final ChatClient chatClient;
    private final PromptTemplate nameGenerationPromptTemplate;
    private final NameRepository nameRepository;
    private final QualityGateService qualityGateService;
    private final DeduplicationService deduplicationService;
    private final int maxGenerationAttempts;

    public NameGenerationService(
            ChatClient chatClient,
            PromptTemplate nameGenerationPromptTemplate,
            NameRepository nameRepository,
            QualityGateService qualityGateService,
            DeduplicationService deduplicationService,
            @Value("${app.generation.max-attempts:3}") int maxGenerationAttempts) {
        this.chatClient = chatClient;
        this.nameGenerationPromptTemplate = nameGenerationPromptTemplate;
        this.nameRepository = nameRepository;
        this.qualityGateService = qualityGateService;
        this.deduplicationService = deduplicationService;
        this.maxGenerationAttempts = maxGenerationAttempts;
    }

    public String testPrompt(String input) {
        return chatClient.prompt(input).call().content();
    }

    public List<NameSuggestion> generateNameSuggestions(Race race, Gender gender, int count) {
        return generateNameSuggestions(race, gender, count, curatedExamples(race, gender));
    }

    private String curatedExamples(Race race, Gender gender) {
        return nameRepository
                .findByRaceAndGenderAndStatusAndSource(race, gender, NameStatus.ACTIVE, NameSource.CURATED)
                .stream()
                .map(Name::getDisplayName)
                .collect(Collectors.joining(", "));
    }

    private List<NameSuggestion> generateNameSuggestions(Race race, Gender gender, int count, String examples) {
        String promptText = nameGenerationPromptTemplate.render(
                Map.of("race", race.name(), "gender", gender.name(), "count", count, "examples", examples));

        return generateNameSuggestions(promptText);
    }

    public List<NameSuggestion> generateNameSuggestions(String promptText) {
        return chatClient
                .prompt(promptText)
                .call()
                .entity(new ParameterizedTypeReference<List<NameSuggestion>>() {});
    }

    /**
     * Generates up to {@code count} names surviving quality-gate and dedup
     * filtering, bounded-retrying the whole generate -> filter step (up to
     * {@code app.generation.max-attempts}, default 3) both when structured
     * output fails to parse and when a successful attempt under-yields (too
     * many candidates rejected by the quality gate or as duplicates). Each
     * retry only asks the model for the remaining shortfall. This does not
     * insert anything -- callers (the Week 3 replenishment path) own writing
     * survivors via NameInsertDao and logging every attempt to generation_log.
     */
    public List<String> generateValidatedNames(Race race, Gender gender, int count) {
        String examples = curatedExamples(race, gender);
        List<String> survivors = new ArrayList<>();
        for (int attempt = 1; attempt <= maxGenerationAttempts && survivors.size() < count; attempt++) {
            List<NameSuggestion> suggestions;
            try {
                suggestions = generateNameSuggestions(race, gender, count - survivors.size(), examples);
            } catch (RuntimeException parseFailure) {
                // Spring AI's BeanOutputConverter wraps a structured-output parse failure in a bare
                // RuntimeException/IllegalStateException -- no dedicated exception type to catch more
                // narrowly. Logged so a persistently-failing combo leaves a trace even before Week 3's
                // generation_log writes land.
                log.warn(
                        "Structured output parse failure generating {}/{} names (attempt {}/{})",
                        race,
                        gender,
                        attempt,
                        maxGenerationAttempts,
                        parseFailure);
                continue;
            }

            List<String> qualityPassed = suggestions.stream()
                    .map(NameSuggestion::name)
                    .filter(qualityGateService::passesQualityGate)
                    .toList();

            List<String> combined = new ArrayList<>(survivors);
            combined.addAll(qualityPassed);
            survivors = deduplicationService.filterDuplicates(race, gender, combined);
        }
        return survivors.size() > count ? new ArrayList<>(survivors.subList(0, count)) : survivors;
    }
}
