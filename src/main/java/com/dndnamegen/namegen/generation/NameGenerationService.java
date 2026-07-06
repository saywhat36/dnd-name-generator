package com.dndnamegen.namegen.generation;

import com.dndnamegen.namegen.config.PromptTemplateConfig;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.name.dto.NameSuggestion;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final GenerationLogRepository generationLogRepository;
    private final int maxGenerationAttempts;

    public NameGenerationService(
            ChatClient chatClient,
            PromptTemplate nameGenerationPromptTemplate,
            NameRepository nameRepository,
            QualityGateService qualityGateService,
            DeduplicationService deduplicationService,
            GenerationLogRepository generationLogRepository,
            @Value("${app.generation.max-attempts:3}") int maxGenerationAttempts) {
        this.chatClient = chatClient;
        this.nameGenerationPromptTemplate = nameGenerationPromptTemplate;
        this.nameRepository = nameRepository;
        this.qualityGateService = qualityGateService;
        this.deduplicationService = deduplicationService;
        this.generationLogRepository = generationLogRepository;
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
            int requested = count - survivors.size();
            List<NameSuggestion> suggestions;
            try {
                suggestions = generateNameSuggestions(race, gender, requested, examples);
            } catch (RuntimeException parseFailure) {
                // Spring AI's BeanOutputConverter wraps a structured-output parse failure in a bare
                // RuntimeException/IllegalStateException -- no dedicated exception type to catch more
                // narrowly.
                log.warn(
                        "Structured output parse failure generating {}/{} names (attempt {}/{})",
                        race,
                        gender,
                        attempt,
                        maxGenerationAttempts,
                        parseFailure);
                generationLogRepository.save(GenerationLog.parseFailure(
                        race,
                        gender,
                        GenerationMode.STANDARD,
                        PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION,
                        requested,
                        parseFailure.getMessage()));
                continue;
            }

            List<String> qualityPassed = suggestions.stream()
                    .map(NameSuggestion::name)
                    .filter(qualityGateService::passesQualityGate)
                    .toList();
            int rejectedQuality = suggestions.size() - qualityPassed.size();

            List<String> combined = new ArrayList<>(survivors);
            combined.addAll(qualityPassed);
            List<String> updatedSurvivors = deduplicationService.filterDuplicates(race, gender, combined);
            int accepted = acceptedThisAttempt(survivors, qualityPassed, updatedSurvivors);
            int rejectedDuplicate = qualityPassed.size() - accepted;
            survivors = updatedSurvivors;

            generationLogRepository.save(GenerationLog.success(
                    race,
                    gender,
                    GenerationMode.STANDARD,
                    PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION,
                    requested,
                    accepted,
                    rejectedDuplicate,
                    rejectedQuality));
        }
        return survivors.size() > count ? new ArrayList<>(survivors.subList(0, count)) : survivors;
    }

    /**
     * Counts how many of this attempt's quality-passed candidates actually landed in
     * {@code updatedSurvivors}, by membership rather than {@code updatedSurvivors.size() -
     * previousSurvivors.size()}. A size delta would go negative (and inflate the rejected-duplicate
     * count) if a name from {@code previousSurvivors} got dropped on re-filtering -- possible once
     * concurrent writers exist (Week 3+), since DeduplicationService re-queries the DB fresh each
     * call and a name accepted as non-duplicate in an earlier attempt can collide with a row another
     * process inserted in between attempts.
     */
    private static int acceptedThisAttempt(
            List<String> previousSurvivors, List<String> qualityPassed, List<String> updatedSurvivors) {
        Map<String, Integer> remaining = new HashMap<>();
        for (String survivor : updatedSurvivors) {
            remaining.merge(survivor, 1, Integer::sum);
        }
        for (String survivor : previousSurvivors) {
            remaining.merge(survivor, -1, Integer::sum);
        }
        int accepted = 0;
        for (String candidate : qualityPassed) {
            Integer count = remaining.get(candidate);
            if (count != null && count > 0) {
                accepted++;
                remaining.put(candidate, count - 1);
            }
        }
        return accepted;
    }
}
