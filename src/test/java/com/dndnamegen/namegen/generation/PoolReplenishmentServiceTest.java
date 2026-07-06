package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.config.PromptTemplateConfig;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameInsertDao;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.name.Race;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PoolReplenishmentServiceTest {

    private final NameGenerationService nameGenerationService = mock(NameGenerationService.class);
    private final NameRepository nameRepository = mock(NameRepository.class);
    private final NameInsertDao nameInsertDao = mock(NameInsertDao.class);
    private final GenerationLogRepository generationLogRepository = mock(GenerationLogRepository.class);

    private PoolReplenishmentService service(int capPerCombo, int batchSize, int maxCallsPerDay) {
        return new PoolReplenishmentService(
                nameGenerationService,
                nameRepository,
                nameInsertDao,
                generationLogRepository,
                capPerCombo,
                batchSize,
                maxCallsPerDay,
                "gemini",
                "gemini-2.5-flash");
    }

    private void stubCounts(long aiGeneratedCount, long curatedCount) {
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.AI_GENERATED))
                .thenReturn(aiGeneratedCount);
        when(nameRepository.countByRaceAndGenderAndStatusAndSource(
                        Race.ELF, Gender.FEMININE, NameStatus.ACTIVE, NameSource.CURATED))
                .thenReturn(curatedCount);
    }

    private GenerationLog stubSavedLogWithId(long id) {
        GenerationLog savedLog = mock(GenerationLog.class);
        when(savedLog.getId()).thenReturn(id);
        when(generationLogRepository.save(any(GenerationLog.class))).thenReturn(savedLog);
        return savedLog;
    }

    @Test
    void replenish_should_SkipGeneration_When_PoolAlreadyAtCap() {
        stubCounts(20, 10);

        service(20, 5, 200).replenish(Race.ELF, Gender.FEMININE);

        verify(nameGenerationService, never()).generateValidatedNames(any(), any(), anyInt());
        verify(nameInsertDao, never()).insertGenerated(any(), any(), anyList(), any(), any(), any(), any());
        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().isParseSuccess()).isFalse();
        assertThat(logCaptor.getValue().getErrorMessage()).contains("pool at cap");
    }

    @Test
    void replenish_should_SkipGeneration_When_NoCuratedExamplesExistForCombo() {
        stubCounts(0, 0);

        service(20, 5, 200).replenish(Race.ELF, Gender.FEMININE);

        verify(nameGenerationService, never()).generateValidatedNames(any(), any(), anyInt());
        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().isParseSuccess()).isFalse();
        assertThat(logCaptor.getValue().getErrorMessage()).contains("no CURATED examples");
    }

    @Test
    void replenish_should_SkipGeneration_When_DailyBudgetAlreadyExhausted() {
        stubCounts(0, 10);

        service(20, 5, 0).replenish(Race.ELF, Gender.FEMININE);

        verify(nameGenerationService, never()).generateValidatedNames(any(), any(), anyInt());
        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getErrorMessage()).contains("budget exhausted");
    }

    @Test
    void replenish_should_GenerateAndInsertSurvivors_When_AllChecksPass() {
        stubCounts(10, 3);
        stubSavedLogWithId(42L);
        when(nameGenerationService.generateValidatedNames(Race.ELF, Gender.FEMININE, 5))
                .thenReturn(List.of("Aelric", "Sylvaine"));
        when(nameInsertDao.insertGenerated(
                        Race.ELF, Gender.FEMININE, List.of("Aelric", "Sylvaine"), "gemini", "gemini-2.5-flash",
                        PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION, 42L))
                .thenReturn(2);

        service(20, 5, 200).replenish(Race.ELF, Gender.FEMININE);

        verify(nameGenerationService).generateValidatedNames(Race.ELF, Gender.FEMININE, 5);
        verify(nameInsertDao)
                .insertGenerated(
                        Race.ELF, Gender.FEMININE, List.of("Aelric", "Sylvaine"), "gemini", "gemini-2.5-flash",
                        PromptTemplateConfig.NAME_GENERATION_PROMPT_VERSION, 42L);
        verify(generationLogRepository).save(any(GenerationLog.class));
    }

    @Test
    void replenish_should_RequestOnlyRemainingHeadroom_When_PoolIsCloseToCap() {
        stubCounts(18, 3);
        stubSavedLogWithId(1L);
        when(nameGenerationService.generateValidatedNames(any(), any(), anyInt())).thenReturn(List.of());
        when(nameInsertDao.insertGenerated(any(), any(), anyList(), anyString(), anyString(), anyString(), any()))
                .thenReturn(0);

        service(20, 5, 200).replenish(Race.ELF, Gender.FEMININE);

        verify(nameGenerationService).generateValidatedNames(Race.ELF, Gender.FEMININE, 2);
    }

    @Test
    void replenish_should_LogFailureAndNotPropagate_When_GenerationThrows() {
        stubCounts(0, 3);
        when(nameGenerationService.generateValidatedNames(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        service(20, 5, 200).replenish(Race.ELF, Gender.FEMININE);

        verify(nameInsertDao, never()).insertGenerated(any(), any(), anyList(), any(), any(), any(), any());
        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().isParseSuccess()).isFalse();
        assertThat(logCaptor.getValue().getErrorMessage()).contains("boom");
    }

    @Test
    void replenish_should_LogExactlyOnce_When_InsertFailsAfterSuccessfulGeneration() {
        stubCounts(0, 3);
        stubSavedLogWithId(7L);
        when(nameGenerationService.generateValidatedNames(any(), any(), anyInt()))
                .thenReturn(List.of("Aelric"));
        when(nameInsertDao.insertGenerated(any(), any(), anyList(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("connection reset"));

        service(20, 5, 200).replenish(Race.ELF, Gender.FEMININE);

        // Exactly one generation_log row for the whole cycle -- not one for the successful
        // generation step and a second for the insert failure. See docs/DECISIONS.md.
        ArgumentCaptor<GenerationLog> logCaptor = ArgumentCaptor.forClass(GenerationLog.class);
        verify(generationLogRepository, times(1)).save(logCaptor.capture());
        assertThat(logCaptor.getValue().isParseSuccess()).isTrue();
        assertThat(logCaptor.getValue().getNamesAccepted()).isEqualTo(1);
    }

    @Test
    void replenish_should_SkipSecondCall_When_FirstCallForSameComboIsStillInFlight() throws InterruptedException {
        stubCounts(0, 3);
        stubSavedLogWithId(1L);
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        when(nameGenerationService.generateValidatedNames(any(), any(), anyInt())).thenAnswer(invocation -> {
            generationStarted.countDown();
            releaseGeneration.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        when(nameInsertDao.insertGenerated(any(), any(), anyList(), anyString(), anyString(), anyString(), any()))
                .thenReturn(0);
        PoolReplenishmentService service = service(20, 5, 200);

        Thread firstCall = new Thread(() -> service.replenish(Race.ELF, Gender.FEMININE));
        firstCall.start();
        assertThat(generationStarted.await(5, TimeUnit.SECONDS)).isTrue();

        service.replenish(Race.ELF, Gender.FEMININE);

        releaseGeneration.countDown();
        firstCall.join(5000);

        verify(nameGenerationService, times(1)).generateValidatedNames(any(), any(), anyInt());
    }
}
