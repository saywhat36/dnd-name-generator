package com.dndnamegen.namegen.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.submission.NameSubmission;
import com.dndnamegen.namegen.submission.NameSubmissionRepository;
import com.dndnamegen.namegen.submission.SubmissionInsertDao;
import com.dndnamegen.namegen.submission.SubmissionStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminSubmissionServiceTest {

    private static final Long REVIEWER_OWNER_ID = 42L;

    private final NameSubmissionRepository nameSubmissionRepository = mock(NameSubmissionRepository.class);
    private final SubmissionInsertDao submissionInsertDao = mock(SubmissionInsertDao.class);
    private final AdminSubmissionService adminSubmissionService =
            new AdminSubmissionService(nameSubmissionRepository, submissionInsertDao);

    private static NameSubmission pendingSubmission() {
        return new NameSubmission(1L, "Aelar", Race.ELF, Gender.MASCULINE);
    }

    @Test
    void approve_should_InsertNameAndMarkApproved_When_SubmissionIsPending() {
        NameSubmission submission = pendingSubmission();
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(submission));
        when(submissionInsertDao.insertSubmitted("Aelar", Race.ELF, Gender.MASCULINE)).thenReturn(1);

        boolean result = adminSubmissionService.approve(7L, REVIEWER_OWNER_ID);

        assertThat(result).isTrue();
        verify(submissionInsertDao).insertSubmitted("Aelar", Race.ELF, Gender.MASCULINE);
        verify(nameSubmissionRepository)
                .updateStatus(eq(7L), eq(SubmissionStatus.APPROVED), eq(REVIEWER_OWNER_ID), any());
    }

    /**
     * The name may already exist (insertSubmitted's ON CONFLICT DO NOTHING returns 0) --
     * approve still marks the submission APPROVED, since the reviewer's decision is the atomic
     * unit being recorded here, not whether a new row happened to be created.
     */
    @Test
    void approve_should_StillMarkApproved_When_NameAlreadyExists() {
        NameSubmission submission = pendingSubmission();
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(submission));
        when(submissionInsertDao.insertSubmitted("Aelar", Race.ELF, Gender.MASCULINE)).thenReturn(0);

        boolean result = adminSubmissionService.approve(7L, REVIEWER_OWNER_ID);

        assertThat(result).isTrue();
        verify(nameSubmissionRepository)
                .updateStatus(eq(7L), eq(SubmissionStatus.APPROVED), eq(REVIEWER_OWNER_ID), any());
    }

    @Test
    void approve_should_ReturnFalse_When_SubmissionNotFound() {
        when(nameSubmissionRepository.findById(999L)).thenReturn(Optional.empty());

        boolean result = adminSubmissionService.approve(999L, REVIEWER_OWNER_ID);

        assertThat(result).isFalse();
        verify(submissionInsertDao, never()).insertSubmitted(any(), any(), any());
        verify(nameSubmissionRepository, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void approve_should_ReturnFalse_When_SubmissionIsNotPending() {
        NameSubmission alreadyResolved = mock(NameSubmission.class);
        when(alreadyResolved.getStatus()).thenReturn(SubmissionStatus.APPROVED);
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(alreadyResolved));

        boolean result = adminSubmissionService.approve(7L, REVIEWER_OWNER_ID);

        assertThat(result).isFalse();
        verify(submissionInsertDao, never()).insertSubmitted(any(), any(), any());
        verify(nameSubmissionRepository, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void reject_should_MarkRejectedWithoutInsertingName_When_SubmissionIsPending() {
        NameSubmission submission = pendingSubmission();
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(submission));

        boolean result = adminSubmissionService.reject(7L, REVIEWER_OWNER_ID);

        assertThat(result).isTrue();
        verify(submissionInsertDao, never()).insertSubmitted(any(), any(), any());
        verify(nameSubmissionRepository)
                .updateStatus(eq(7L), eq(SubmissionStatus.REJECTED), eq(REVIEWER_OWNER_ID), any());
    }

    @Test
    void reject_should_ReturnFalse_When_SubmissionNotFound() {
        when(nameSubmissionRepository.findById(999L)).thenReturn(Optional.empty());

        boolean result = adminSubmissionService.reject(999L, REVIEWER_OWNER_ID);

        assertThat(result).isFalse();
        verify(nameSubmissionRepository, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void reject_should_ReturnFalse_When_SubmissionIsNotPending() {
        NameSubmission alreadyResolved = mock(NameSubmission.class);
        when(alreadyResolved.getStatus()).thenReturn(SubmissionStatus.REJECTED);
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(alreadyResolved));

        boolean result = adminSubmissionService.reject(7L, REVIEWER_OWNER_ID);

        assertThat(result).isFalse();
        verify(nameSubmissionRepository, never()).updateStatus(any(), any(), any(), any());
    }
}
