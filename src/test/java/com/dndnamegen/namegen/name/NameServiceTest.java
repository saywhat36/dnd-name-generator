package com.dndnamegen.namegen.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class NameServiceTest {

    private final NameRepository nameRepository = mock(NameRepository.class);
    private final NameService nameService = new NameService(nameRepository);

    @Test
    void getNames_should_QueryActiveCuratedNamesOnly_When_Called() {
        Name curatedName = mock(Name.class);
        when(nameRepository.findByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.CURATED))
                .thenReturn(List.of(curatedName));

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE);

        assertThat(result).containsExactly(curatedName);
        verify(nameRepository)
                .findByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.CURATED);
    }
}
