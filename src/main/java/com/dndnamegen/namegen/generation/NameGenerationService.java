package com.dndnamegen.namegen.generation;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.name.dto.NameSuggestion;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
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

    private final ChatClient chatClient;
    private final PromptTemplate nameGenerationPromptTemplate;
    private final NameRepository nameRepository;

    public NameGenerationService(
            ChatClient chatClient, PromptTemplate nameGenerationPromptTemplate, NameRepository nameRepository) {
        this.chatClient = chatClient;
        this.nameGenerationPromptTemplate = nameGenerationPromptTemplate;
        this.nameRepository = nameRepository;
    }

    public String testPrompt(String input) {
        return chatClient.prompt(input).call().content();
    }

    public List<NameSuggestion> generateNameSuggestions(Race race, Gender gender, int count) {
        String examples = nameRepository
                .findByRaceAndGenderAndStatusAndSource(race, gender, NameStatus.ACTIVE, NameSource.CURATED)
                .stream()
                .map(Name::getDisplayName)
                .collect(Collectors.joining(", "));

        String promptText = nameGenerationPromptTemplate.render(
                Map.of("race", race, "gender", gender, "count", count, "examples", examples));

        return generateNameSuggestions(promptText);
    }

    public List<NameSuggestion> generateNameSuggestions(String promptText) {
        return chatClient
                .prompt(promptText)
                .call()
                .entity(new ParameterizedTypeReference<List<NameSuggestion>>() {});
    }
}
