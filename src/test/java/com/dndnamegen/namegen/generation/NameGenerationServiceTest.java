package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.ParameterizedTypeReference;

class NameGenerationServiceTest {

    private static final String TEMPLATE = "Generate {count} {race} names ({gender}). Examples: {examples}";

    private final ChatClient chatClient = mock(ChatClient.class);
    private final NameRepository nameRepository = mock(NameRepository.class);
    private final PromptTemplate nameGenerationPromptTemplate = new PromptTemplate(TEMPLATE);
    private final NameGenerationService nameGenerationService =
            new NameGenerationService(chatClient, nameGenerationPromptTemplate, nameRepository);

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
}
