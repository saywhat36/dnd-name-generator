package com.dndnamegen.namegen.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.generation.QualityGateService;
import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.Race;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NameSubmissionServiceTest {

    private static final Identity IDENTITY = Identity.of(42L, "session-1");

    private final NameSubmissionRepository nameSubmissionRepository = mock(NameSubmissionRepository.class);
    private final NameRepository nameRepository = mock(NameRepository.class);
    private final QualityGateService qualityGateService = mock(QualityGateService.class);
    private final NameSubmissionService nameSubmissionService =
            new NameSubmissionService(nameSubmissionRepository, nameRepository, qualityGateService);

    @Test
    void submit_should_SaveSubmission_When_GatePassesAndNoDuplicate() {
        when(qualityGateService.passesQualityGate("Aelar")).thenReturn(true);
        when(nameRepository.existsByNormalizedNameAndRaceAndGender("aelar", Race.ELF, Gender.MASCULINE))
                .thenReturn(false);
        when(nameSubmissionRepository.existsByNormalizedNameAndRaceAndGenderAndStatus(
                        "aelar", Race.ELF, Gender.MASCULINE, SubmissionStatus.PENDING))
                .thenReturn(false);
        NameSubmission saved = new NameSubmission(42L, "Aelar", Race.ELF, Gender.MASCULINE);
        when(nameSubmissionRepository.save(any(NameSubmission.class))).thenReturn(saved);

        NameSubmission result = nameSubmissionService.submit(IDENTITY, Race.ELF, Gender.MASCULINE, "Aelar");

        assertThat(result).isSameAs(saved);
    }

    @Test
    void submit_should_ThrowBadRequest_When_QualityGateFails() {
        when(qualityGateService.passesQualityGate("###")).thenReturn(false);

        assertThatThrownBy(() -> nameSubmissionService.submit(IDENTITY, Race.ELF, Gender.MASCULINE, "###"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(nameSubmissionRepository, never()).save(any());
    }

    @Test
    void submit_should_ThrowConflict_When_LiveNameAlreadyExists() {
        when(qualityGateService.passesQualityGate("Aelar")).thenReturn(true);
        when(nameRepository.existsByNormalizedNameAndRaceAndGender("aelar", Race.ELF, Gender.MASCULINE))
                .thenReturn(true);

        assertThatThrownBy(() -> nameSubmissionService.submit(IDENTITY, Race.ELF, Gender.MASCULINE, "Aelar"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(nameSubmissionRepository, never()).save(any());
    }

    @Test
    void submit_should_ThrowConflict_When_PendingSubmissionAlreadyExists() {
        when(qualityGateService.passesQualityGate("Aelar")).thenReturn(true);
        when(nameRepository.existsByNormalizedNameAndRaceAndGender("aelar", Race.ELF, Gender.MASCULINE))
                .thenReturn(false);
        when(nameSubmissionRepository.existsByNormalizedNameAndRaceAndGenderAndStatus(
                        "aelar", Race.ELF, Gender.MASCULINE, SubmissionStatus.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> nameSubmissionService.submit(IDENTITY, Race.ELF, Gender.MASCULINE, "Aelar"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(nameSubmissionRepository, never()).save(any());
    }

    /**
     * Simulates two concurrent submits for the same (normalized_name, race, gender): both pass the
     * existsBy pre-checks, one wins the insert, the other's save() throws off uq_submissions_pending
     * and must surface as a 409, not propagate as an unmapped 500. Mirrors
     * NameReportServiceTest's concurrent-race case.
     */
    @Test
    void submit_should_ThrowConflict_When_ConcurrentInsertViolatesUniqueConstraint() {
        when(qualityGateService.passesQualityGate("Aelar")).thenReturn(true);
        when(nameRepository.existsByNormalizedNameAndRaceAndGender("aelar", Race.ELF, Gender.MASCULINE))
                .thenReturn(false);
        when(nameSubmissionRepository.existsByNormalizedNameAndRaceAndGenderAndStatus(
                        "aelar", Race.ELF, Gender.MASCULINE, SubmissionStatus.PENDING))
                .thenReturn(false);
        when(nameSubmissionRepository.save(any(NameSubmission.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> nameSubmissionService.submit(IDENTITY, Race.ELF, Gender.MASCULINE, "Aelar"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listMySubmissions_should_ReturnOwnersSubmissions_When_TheyHaveAny() {
        NameSubmission first = new NameSubmission(42L, "Aelar", Race.ELF, Gender.MASCULINE);
        NameSubmission second = new NameSubmission(42L, "Borin", Race.DWARF, Gender.MASCULINE);
        when(nameSubmissionRepository.findBySubmitterIdOrderByCreatedAtDescIdDesc(42L))
                .thenReturn(List.of(second, first));

        List<NameSubmission> result = nameSubmissionService.listMySubmissions(IDENTITY);

        assertThat(result).containsExactly(second, first);
    }

    @Test
    void listMySubmissions_should_ReturnEmptyList_When_OwnerHasNoSubmissions() {
        when(nameSubmissionRepository.findBySubmitterIdOrderByCreatedAtDescIdDesc(42L)).thenReturn(List.of());

        List<NameSubmission> result = nameSubmissionService.listMySubmissions(IDENTITY);

        assertThat(result).isEmpty();
    }
}
