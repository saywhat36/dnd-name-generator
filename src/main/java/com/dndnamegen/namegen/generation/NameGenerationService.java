package com.dndnamegen.namegen.generation;

import com.dndnamegen.namegen.name.dto.NameSuggestion;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Week 1 proved the ChatClient pipe works end to end (plain text, no prompt
 * template, no structured output) via testPrompt. Week 2 adds the structured
 * output path used by the real generation flow; the prompt template and
 * few-shot examples land in a follow-up change.
 */
@Service
public class NameGenerationService {

    private final ChatClient chatClient;

    public NameGenerationService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String testPrompt(String input) {
        return chatClient.prompt(input).call().content();
    }

    public List<NameSuggestion> generateNameSuggestions(String promptText) {
        return chatClient
                .prompt(promptText)
                .call()
                .entity(new ParameterizedTypeReference<List<NameSuggestion>>() {});
    }
}
