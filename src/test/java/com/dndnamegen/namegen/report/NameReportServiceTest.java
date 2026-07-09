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
    void reportName_should_ReturnExistingRow_When_OwnerAlreadyReportedThisName() {
        NameReport existing = new NameReport(42L, 1L, "original reason");
        when(nameReportRepository.findByOwnerIdAndNameId(42L, 1L)).thenReturn(Optional.of(existing));

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "a different reason");

        assertThat(result).isSameAs(existing);
        verify(nameReportRepository, never()).save(any());
    }

    @Test
    void reportName_should_SaveNewRow_When_NotYetReported() {
        when(nameReportRepository.findByOwnerIdAndNameId(42L, 1L)).thenReturn(Optional.empty());
        NameReport saved = new NameReport(42L, 1L, "reason");
        when(nameReportRepository.save(any(NameReport.class))).thenReturn(saved);

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "reason");

        assertThat(result).isSameAs(saved);
    }

    /**
     * Simulates two concurrent reportName calls for the same (ownerId, nameId): both pass
     * the initial findByOwnerIdAndNameId check, one wins the insert, the other's save()
     * throws off the unique constraint and must return the winner's row instead of propagating.
     */
    @Test
    void reportName_should_ReturnWinnersRow_When_ConcurrentReportRaceViolatesUniqueConstraint() {
        NameReport winnersRow = new NameReport(42L, 1L, "reason");
        when(nameReportRepository.findByOwnerIdAndNameId(42L, 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(nameReportRepository.save(any(NameReport.class))).thenThrow(new DataIntegrityViolationException("dup"));

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "reason");

        assertThat(result).isSameAs(winnersRow);
    }

    /**
     * Reports are keyed on Identity.ownerId() as of slice 6, not sessionId -- name_reports
     * gained an owner_id column and session_id was relaxed to nullable (see
     * docs/DECISIONS.md). reportName must ignore sessionId() entirely now.
     */
    @Test
    void reportName_should_UseOwnerIdNotSessionId() {
        when(nameReportRepository.findByOwnerIdAndNameId(42L, 1L)).thenReturn(Optional.empty());
        NameReport saved = new NameReport(42L, 1L, "reason");
        when(nameReportRepository.save(any(NameReport.class))).thenReturn(saved);

        NameReport result = nameReportService.reportName(IDENTITY, 1L, "reason");

        assertThat(result).isSameAs(saved);
        verify(nameReportRepository).findByOwnerIdAndNameId(42L, 1L);
    }

    @Test
    void getReportedNameIds_should_ReturnIdsAsASet_When_OwnerHasReports() {
        when(nameReportRepository.findNameIdByOwnerId(42L)).thenReturn(List.of(2L, 1L));

        Set<Long> result = nameReportService.getReportedNameIds(IDENTITY);

        assertThat(result).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void getReportedNameIds_should_ReturnEmptySet_When_OwnerHasNoReports() {
        when(nameReportRepository.findNameIdByOwnerId(42L)).thenReturn(List.of());

        Set<Long> result = nameReportService.getReportedNameIds(IDENTITY);

        assertThat(result).isEmpty();
    }

    /**
     * Slice 7: the browse pages are public, so getReportedNameIds may now be called with an
     * anonymous Identity (see docs/DECISIONS.md) -- matches FavoriteServiceTest's equivalent
     * case.
     */
    @Test
    void getReportedNameIds_should_ReturnEmptySet_When_IdentityIsAnonymous() {
        Set<Long> result = nameReportService.getReportedNameIds(Identity.anonymous("session-1"));

        assertThat(result).isEmpty();
        verify(nameReportRepository, never()).findNameIdByOwnerId(any());
    }
}
