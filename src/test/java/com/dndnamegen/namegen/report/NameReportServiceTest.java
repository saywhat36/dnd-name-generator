package com.dndnamegen.namegen.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.identity.Identity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class NameReportServiceTest {

    private static final Identity IDENTITY = Identity.of(42L, "session-1");

    private final NameReportRepository nameReportRepository = mock(NameReportRepository.class);
    private final NameReportService nameReportService = new NameReportService(nameReportRepository);

    @Test
    void reportName_should_ReturnExistingRow_When_SessionAlreadyReportedThisName() {
        NameReport existing = new NameReport("session-1", 1L, "original reason");
        when(nameReportRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.of(existing));

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "a different reason");

        assertThat(result).isSameAs(existing);
        verify(nameReportRepository, never()).save(any());
    }

    @Test
    void reportName_should_SaveNewRow_When_NotYetReported() {
        when(nameReportRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.empty());
        NameReport saved = new NameReport("session-1", 1L, "reason");
        when(nameReportRepository.save(any(NameReport.class))).thenReturn(saved);

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "reason");

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

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "reason");

        assertThat(result).isSameAs(winnersRow);
    }

    /**
     * Reports are keyed on Identity.sessionId(), not ownerId, even though every Identity now
     * carries an authenticated owner id too -- see docs/DECISIONS.md, identity resolution slice.
     * name_reports has no owner_id column, so reportName must ignore ownerId() entirely.
     */
    @Test
    void reportName_should_UseSessionIdNotOwnerId() {
        when(nameReportRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.empty());
        NameReport saved = new NameReport("session-1", 1L, "reason");
        when(nameReportRepository.save(any(NameReport.class))).thenReturn(saved);

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "reason");

        assertThat(result).isSameAs(saved);
        verify(nameReportRepository).findBySessionIdAndNameId("session-1", 1L);
    }

    @Test
    void getReportedNameIds_should_ReturnIdsAsASet_When_SessionHasReports() {
        when(nameReportRepository.findNameIdBySessionId("session-1")).thenReturn(List.of(2L, 1L));

        Set<Long> result = nameReportService.getReportedNameIds(IDENTITY);

        assertThat(result).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void getReportedNameIds_should_ReturnEmptySet_When_SessionHasNoReports() {
        when(nameReportRepository.findNameIdBySessionId("session-1")).thenReturn(List.of());

        Set<Long> result = nameReportService.getReportedNameIds(IDENTITY);

        assertThat(result).isEmpty();
    }
}
