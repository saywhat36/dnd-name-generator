package com.dndnamegen.namegen.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NameServiceTest {

    private final NameRepository nameRepository = mock(NameRepository.class);
    private final NameService nameService = new NameService(nameRepository);

    private static Name nameWithSource(NameSource source) {
        Name name = mock(Name.class);
        when(name.getSource()).thenReturn(source);
        return name;
    }

    private static List<Name> aiGeneratedNames(int count) {
        return IntStream.range(0, count).mapToObj(i -> nameWithSource(NameSource.AI_GENERATED)).toList();
    }

    @Test
    void getNames_should_QueryCuratedOnly_When_SourceIsCurated() {
        Name curatedName = mock(Name.class);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.CURATED)))
                .thenReturn(List.of(curatedName));

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED);

        assertThat(result).containsExactly(curatedName);
    }

    @Test
    void getNames_should_QueryUserSubmittedOnly_When_SourceIsUserSubmitted() {
        Name userSubmittedName = nameWithSource(NameSource.USER_SUBMITTED);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.USER_SUBMITTED)))
                .thenReturn(List.of(userSubmittedName));

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.USER_SUBMITTED);

        assertThat(result).containsExactly(userSubmittedName);
    }

    @Test
    void getNames_should_QueryAiGeneratedOnly_When_SourceIsAiGenerated() {
        List<Name> aiNames = aiGeneratedNames(10);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.AI_GENERATED)))
                .thenReturn(aiNames);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        assertThat(result).containsExactlyElementsOf(aiNames);
    }

    @Test
    void getNames_should_QueryEverySource_When_SourceIsAll() {
        Name curatedName = nameWithSource(NameSource.CURATED);
        Name userSubmittedName = nameWithSource(NameSource.USER_SUBMITTED);
        List<Name> aiNames = aiGeneratedNames(10);
        List<Name> allNames = Stream.concat(
                        Stream.of(curatedName, userSubmittedName), aiNames.stream())
                .toList();
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF,
                        Gender.FEMININE,
                        NameStatus.ACTIVE,
                        List.of(NameSource.CURATED, NameSource.AI_GENERATED, NameSource.USER_SUBMITTED)))
                .thenReturn(allNames);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.ALL);

        assertThat(result).containsExactlyElementsOf(allNames);
    }

    /**
     * Issue #98: name serving is a pure read on every source, even the AI source with a nearly
     * empty pool. {@code getNames} no longer schedules generation of any kind -- growing the AI
     * pool is exclusively the user-driven "Generate five more" action (see NameBrowserControllerTest).
     * The strongest regression guard is that NameService no longer collaborates with
     * PoolReplenishmentService at all (it isn't even a constructor dependency); this test pins that
     * browsing a sub-(former-)threshold AI pool returns exactly the served rows.
     */
    @Test
    void getNames_should_ServeWithoutTriggeringGeneration_When_AiPoolIsBelowFormerThreshold() {
        List<Name> belowFormerThresholdPool = aiGeneratedNames(4);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(belowFormerThresholdPool);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        assertThat(result).containsExactlyElementsOf(belowFormerThresholdPool);
    }

    @Test
    void getNames_should_NotIncludeUserSubmittedUnderCuratedFilter() {
        // Issue #81: user-submitted names are no longer folded into CURATED -- the CURATED filter
        // now queries the CURATED source alone, so USER_SUBMITTED can never be requested here.
        Name curatedName = nameWithSource(NameSource.CURATED);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.CURATED)))
                .thenReturn(List.of(curatedName));

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED);

        assertThat(result).containsExactly(curatedName);
        verify(nameRepository)
                .findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.CURATED));
    }

    @Test
    void getNames_should_NotIncludeUserSubmittedUnderAiGeneratedFilter() {
        List<Name> aiNames = aiGeneratedNames(10);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.AI_GENERATED)))
                .thenReturn(aiNames);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        assertThat(result).containsExactlyElementsOf(aiNames);
    }

    @Test
    void flagName_should_ReturnTrue_When_NameExists() {
        when(nameRepository.updateStatus(1L, NameStatus.FLAGGED)).thenReturn(1);

        boolean result = nameService.flagName(1L);

        assertThat(result).isTrue();
    }

    @Test
    void flagName_should_ReturnFalse_When_NameDoesNotExist() {
        when(nameRepository.updateStatus(1L, NameStatus.FLAGGED)).thenReturn(0);

        boolean result = nameService.flagName(1L);

        assertThat(result).isFalse();
    }

    @Test
    void rejectName_should_ReturnTrue_When_NameExists() {
        when(nameRepository.updateStatus(1L, NameStatus.REJECTED)).thenReturn(1);

        boolean result = nameService.rejectName(1L);

        assertThat(result).isTrue();
    }

    @Test
    void rejectName_should_ReturnFalse_When_NameDoesNotExist() {
        when(nameRepository.updateStatus(1L, NameStatus.REJECTED)).thenReturn(0);

        boolean result = nameService.rejectName(1L);

        assertThat(result).isFalse();
    }

    @Test
    void unflagName_should_ReturnTrue_When_NameExists() {
        when(nameRepository.updateStatus(1L, NameStatus.ACTIVE)).thenReturn(1);

        boolean result = nameService.unflagName(1L);

        assertThat(result).isTrue();
    }

    @Test
    void unflagName_should_ReturnFalse_When_NameDoesNotExist() {
        when(nameRepository.updateStatus(1L, NameStatus.ACTIVE)).thenReturn(0);

        boolean result = nameService.unflagName(1L);

        assertThat(result).isFalse();
    }
}
