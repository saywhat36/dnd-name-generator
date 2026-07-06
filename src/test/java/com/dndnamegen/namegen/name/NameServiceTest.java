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
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class NameServiceTest {

    private final NameRepository nameRepository = mock(NameRepository.class);
    private final PoolReplenishmentService poolReplenishmentService = mock(PoolReplenishmentService.class);
    private final NameService nameService = new NameService(nameRepository, poolReplenishmentService, 5);

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
    void getNames_should_QueryAiGeneratedOnly_When_SourceIsAiGenerated() {
        Name aiName = mock(Name.class);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, List.of(NameSource.AI_GENERATED)))
                .thenReturn(List.of(aiName));
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(10L);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        assertThat(result).containsExactly(aiName);
    }

    @Test
    void getNames_should_QueryCuratedAndAiGenerated_When_SourceIsBoth() {
        Name curatedName = mock(Name.class);
        Name aiName = mock(Name.class);
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(
                        Race.ELF,
                        Gender.FEMININE,
                        NameStatus.ACTIVE,
                        List.of(NameSource.CURATED, NameSource.AI_GENERATED)))
                .thenReturn(List.of(curatedName, aiName));
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(10L);

        List<Name> result = nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.BOTH);

        assertThat(result).containsExactly(curatedName, aiName);
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
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(4L);

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
    }

    @Test
    void getNames_should_TriggerReplenish_When_SourceIsBothAndPoolBelowThreshold() {
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(0L);

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.BOTH);

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
    }

    @Test
    void getNames_should_NotTriggerReplenish_When_AiGeneratedPoolAtOrAboveThreshold() {
        when(nameRepository.findByRaceAndGenderAndStatusAndSourceIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(5L);

        nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED);

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
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(0L);
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
}
