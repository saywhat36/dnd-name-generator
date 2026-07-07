package com.dndnamegen.namegen.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class NameReportServiceTest {

    private final NameReportRepository nameReportRepository = mock(NameReportRepository.class);
    private final NameReportService nameReportService = new NameReportService(nameReportRepository);

    @Test
    void reportName_should_ReturnExistingRow_When_SessionAlreadyReportedThisName() {
        NameReport existing = new NameReport("session-1", 1L, "original reason");
        when(nameReportRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.of(existing));

        NameReport result = nameReportService.reportName("session-1", 1L, "a different reason");

        assertThat(result).isSameAs(existing);
        verify(nameReportRepository, never()).save(any());
    }

    @Test
    void reportName_should_SaveNewRow_When_NotYetReported() {
        when(nameReportRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.empty());
        NameReport saved = new NameReport("session-1", 1L, "reason");
        when(nameReportRepository.save(any(NameReport.class))).thenReturn(saved);

        NameReport result = nameReportService.reportName("session-1", 1L, "reason");

        assertThat(result).isSameAs(saved);
    }

    /**
     * Simulates two concurrent reportName calls for the same (sessionId, nameId): both pass
     * the initial findBySessionIdAndNameId check, one wins the insert, the other's save()
     * throws off the unique constraint and must return the winner's row instead of propagating.
     */
    @Test
    void reportName_should_ReturnWinnersRow_When_ConcurrentReportRaceViolatesUniqueConstraint() {
        NameReport winnersRow = new NameReport("session-1", 1L, "reason");
        when(nameReportRepository.findBySessionIdAndNameId("session-1", 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(nameReportRepository.save(any(NameReport.class))).thenThrow(new DataIntegrityViolationException("dup"));

        NameReport result = nameReportService.reportName("session-1", 1L, "reason");

        assertThat(result).isSameAs(winnersRow);
    }
}
