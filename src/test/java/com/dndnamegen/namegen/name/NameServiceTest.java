package com.dndnamegen.namegen.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class NameServiceTest {

    private final NameRepository nameRepository = mock(NameRepository.class);
    private final PoolReplenishmentService poolReplenishmentService = mock(PoolReplenishmentService.class);
    private final NameService nameService = new NameService(nameRepository, poolReplenishmentService, 5);

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
        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    @Test
    void getNames_should_QueryUserSubmittedOnly_When_SourceIsUserSubmitted() {
        Name userSubmittedName = nameWithSource(NameSource.USER_SUBMITTED);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.USER_SUBMITTED)))
                .thenReturn(List.of(userSubmittedName));

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.USER_SUBMITTED);

        assertThat(result).containsExactly(userSubmittedName);
        // No AI in this source, so no replenishment is ever triggered for the user-submitted view.
        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    @Test
    void getNames_should_QueryAiGeneratedOnly_When_SourceIsAiGenerated() {
        List<Name> aiNames = aiGeneratedNames(10);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.AI_GENERATED)))
                .thenReturn(aiNames);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        assertThat(result).containsExactlyElementsOf(aiNames);
        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    @Test
    void getNames_should_QueryCuratedAndAiGenerated_When_SourceIsBoth() {
        Name curatedName = nameWithSource(NameSource.CURATED);
        List<Name> aiNames = aiGeneratedNames(10);
        List<Name> allNames = Stream.concat(Stream.of(curatedName), aiNames.stream()).toList();
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF,
                        Gender.FEMININE,
                        NameStatus.ACTIVE,
                        List.of(NameSource.CURATED, NameSource.AI_GENERATED)))
                .thenReturn(allNames);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.BOTH);

        assertThat(result).containsExactlyElementsOf(allNames);
        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    @Test
    void getNames_should_NotTriggerReplenish_When_SourceIsCuratedOnly() {
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(List.of());

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED);

        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    @Test
    void getNames_should_TriggerReplenish_When_SourceIsAiGeneratedAndPoolBelowThreshold() {
        List<Name> belowThresholdPool = aiGeneratedNames(4);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(belowThresholdPool);

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
    }

    @Test
    void getNames_should_TriggerReplenish_When_SourceIsBothAndPoolBelowThreshold() {
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(List.of());

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.BOTH);

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
    }

    @Test
    void getNames_should_NotTriggerReplenish_When_AiGeneratedPoolAtOrAboveThreshold() {
        List<Name> atThresholdPool = aiGeneratedNames(5);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(atThresholdPool);

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        verify(poolReplenishmentService, never()).replenish(any(), any());
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
        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    @Test
    void getNames_should_NotIncludeUserSubmittedUnderAiGeneratedFilter() {
        List<Name> aiNames = aiGeneratedNames(10);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.AI_GENERATED)))
                .thenReturn(aiNames);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        assertThat(result).containsExactlyElementsOf(aiNames);
        verify(poolReplenishmentService, never()).replenish(any(), any());
    }

    /**
     * PoolReplenishmentService.replenish is @Async in production, so the Spring proxy
     * dispatches the real generation work to another thread and returns immediately --
     * NameService must never itself wait on that work. This test stubs replenish with an
     * Answer that mimics exactly that proxy behavior (hand off to a background thread,
     * return immediately) with a deliberately slow "LLM call" on that background thread,
     * then asserts getNames returns long before the slow work finishes.
     */
    @Test
    void getNames_should_ReturnWithoutBlocking_When_ReplenishSimulatesASlowProvider() throws InterruptedException {
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        CountDownLatch slowProviderCallFinished = new CountDownLatch(1);
        Answer<Void> mimicsAsyncProxyDispatch = invocation -> {
            Thread backgroundReplenishment = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                slowProviderCallFinished.countDown();
            });
            backgroundReplenishment.start();
            return null;
        };
        doAnswer(mimicsAsyncProxyDispatch).when(poolReplenishmentService).replenish(any(), any());

        long start = System.nanoTime();
        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(100);
        assertThat(slowProviderCallFinished.await(5, TimeUnit.SECONDS)).isTrue();
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
