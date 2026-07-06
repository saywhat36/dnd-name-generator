package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.name.dto.NameSuggestion;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;

class NameGenerationServiceTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final NameGenerationService nameGenerationService = new NameGenerationService(chatClient);

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
    }
}
