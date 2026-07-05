package com.dndnamegen.namegen.generation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Week 1: proves the ChatClient pipe works end to end (plain text, no prompt
 * template, no structured output). Week 2 replaces testPrompt with the real
 * generation flow.
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
}
