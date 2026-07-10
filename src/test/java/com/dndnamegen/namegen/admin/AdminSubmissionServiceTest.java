package com.dndnamegen.namegen.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.submission.NameSubmission;
import com.dndnamegen.namegen.submission.NameSubmissionRepository;
import com.dndnamegen.namegen.submission.PendingSubmissionSummary;
import com.dndnamegen.namegen.submission.SubmissionInsertDao;
import com.dndnamegen.namegen.submission.SubmissionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class AdminSubmissionServiceTest {

    private static final Long REVIEWER_OWNER_ID = 42L;

    private final NameSubmissionRepository nameSubmissionRepository = mock(NameSubmissionRepository.class);
    private final SubmissionInsertDao submissionInsertDao = mock(SubmissionInsertDao.class);
    private final AdminSubmissionService adminSubmissionService =
            new AdminSubmissionService(nameSubmissionRepository, submissionInsertDao);

    private static NameSubmission pendingSubmission() {
        return new NameSubmission(1L, "Aelar", Race.ELF, Gender.MASCULINE);
    }

    private static PendingSubmissionSummary summary(Long id, String displayName) {
        PendingSubmissionSummary s = mock(PendingSubmissionSummary.class);
        when(s.getSubmissionId()).thenReturn(id);
        when(s.getDisplayName()).thenReturn(displayName);
        when(s.getRace()).thenReturn(Race.ELF);
        when(s.getGender()).thenReturn(Gender.MASCULINE);
        when(s.getSubmitterUsername()).thenReturn("gandalf");
        when(s.getCreatedAt()).thenReturn(Instant.parse("2026-07-10T12:00:00Z"));
        return s;
    }

    @Test
    void listPendingSubmissions_should_ReturnPageOfViews_When_QueryingAGivenPage() {
        // page 2 (offset 100) of size 50 out of 120 total genuinely has 20 rows (120 - 100) --
        // PageImpl recomputes totalElements from offset + content.size() if a shorter content
        // list makes the supplied total implausible, so the mocked content size must be
        // consistent with the mocked total for this assertion to actually exercise pass-through
        // rather than PageImpl's own defensive override.
        PendingSubmissionSummary summary = summary(7L, "Aelar");
        Pageable pageable = PageRequest.of(2, 50);
        when(nameSubmissionRepository.findPendingSummaries(SubmissionStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(summary), pageable, 101));

        PendingSubmissionsPage result = adminSubmissionService.listPendingSubmissions(2);

        assertThat(result.submissions()).hasSize(1);
        assertThat(result.submissions().get(0).submissionId()).isEqualTo(7L);
        assertThat(result.submissions().get(0).displayName()).isEqualTo("Aelar");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(101);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.hasPrevious()).isTrue();
        assertThat(result.hasNext()).isFalse();
    }

    /**
     * Regression guard for review of #90: a negative page reaching {@code PageRequest.of}
     * unclamped throws {@code IllegalArgumentException}, which this app has no
     * {@code @ControllerAdvice} to map to a handled response -- GET /admin/submissions?page=-1
     * would 500. Clamped to 0 instead, and the returned page reflects the clamp (0), not the raw
     * negative input.
     */
    @Test
    void listPendingSubmissions_should_ClampToFirstPage_When_PageIsNegative() {
        Pageable clampedPageable = PageRequest.of(0, 50);
        when(nameSubmissionRepository.findPendingSummaries(SubmissionStatus.PENDING, clampedPageable))
                .thenReturn(new PageImpl<>(List.of(), clampedPageable, 0));

        PendingSubmissionsPage result = adminSubmissionService.listPendingSubmissions(-1);

        assertThat(result.page()).isEqualTo(0);
        verify(nameSubmissionRepository).findPendingSummaries(SubmissionStatus.PENDING, clampedPageable);
    }

    /**
     * hasPrevious/hasNext are plain arithmetic on the record's own fields -- no repository
     * involvement needed to exercise every boundary (first page, middle page, last page).
     */
    @Test
    void hasPreviousAndHasNext_should_ReflectPagePosition_When_ConstructedDirectly() {
        PendingSubmissionsPage firstOfThree = new PendingSubmissionsPage(List.of(), 0, 3, 0);
        PendingSubmissionsPage middleOfThree = new PendingSubmissionsPage(List.of(), 1, 3, 0);
        PendingSubmissionsPage lastOfThree = new PendingSubmissionsPage(List.of(), 2, 3, 0);
        PendingSubmissionsPage onlyPage = new PendingSubmissionsPage(List.of(), 0, 1, 0);

        assertThat(firstOfThree.hasPrevious()).isFalse();
        assertThat(firstOfThree.hasNext()).isTrue();
        assertThat(middleOfThree.hasPrevious()).isTrue();
        assertThat(middleOfThree.hasNext()).isTrue();
        assertThat(lastOfThree.hasPrevious()).isTrue();
        assertThat(lastOfThree.hasNext()).isFalse();
        assertThat(onlyPage.hasPrevious()).isFalse();
        assertThat(onlyPage.hasNext()).isFalse();
    }

    @Test
    void bulkApprove_should_ApproveEachId_When_AllArePending() {
        NameSubmission first = pendingSubmission();
        NameSubmission second = pendingSubmission();
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(first));
        when(nameSubmissionRepository.findById(8L)).thenReturn(Optional.of(second));

        adminSubmissionService.bulkApprove(List.of(7L, 8L), REVIEWER_OWNER_ID);

        verify(submissionInsertDao, times(2)).insertSubmitted("Aelar", Race.ELF, Gender.MASCULINE);
        verify(nameSubmissionRepository)
                .updateStatus(eq(7L), eq(SubmissionStatus.APPROVED), eq(REVIEWER_OWNER_ID), any());
        verify(nameSubmissionRepository)
                .updateStatus(eq(8L), eq(SubmissionStatus.APPROVED), eq(REVIEWER_OWNER_ID), any());
    }

    /**
     * Best-effort, not all-or-nothing: an id that's already resolved (a race with another admin,
     * or a stale checkbox) is silently skipped by the underlying single-id approve, not thrown --
     * the rest of the batch must still go through.
     */
    @Test
    void bulkApprove_should_SkipAlreadyResolvedIds_When_OneIdInBatchIsNotPending() {
        NameSubmission stillPending = pendingSubmission();
        NameSubmission alreadyResolved = mock(NameSubmission.class);
        when(alreadyResolved.getStatus()).thenReturn(SubmissionStatus.APPROVED);
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(stillPending));
        when(nameSubmissionRepository.findById(8L)).thenReturn(Optional.of(alreadyResolved));

        adminSubmissionService.bulkApprove(List.of(7L, 8L), REVIEWER_OWNER_ID);

        verify(submissionInsertDao, times(1)).insertSubmitted(any(), any(), any());
        verify(nameSubmissionRepository)
                .updateStatus(eq(7L), eq(SubmissionStatus.APPROVED), eq(REVIEWER_OWNER_ID), any());
        verify(nameSubmissionRepository, never())
                .updateStatus(eq(8L), any(), any(), any());
    }

    @Test
    void bulkReject_should_RejectEachId_When_AllArePending() {
        NameSubmission first = pendingSubmission();
        NameSubmission second = pendingSubmission();
        when(nameSubmissionRepository.findById(7L)).thenReturn(Optional.of(first));
        when(nameSubmissionRepository.findById(8L)).thenReturn(Optional.of(second));

        adminSubmissionService.bulkReject(List.of(7L, 8L), REVIEWER_OWNER_ID);

        verify(submissionInsertDao, never()).insertSubmitted(any(), any(), any());
        verify(nameSubmissionRepository)
                .updateStatus(eq(7L), eq(SubmissionStatus.REJECTED), eq(REVIEWER_OWNER_ID), any());
        verify(nameSubmissionRepository)
                .updateStatus(eq(8L), eq(SubmissionStatus.REJECTED), eq(REVIEWER_OWNER_ID), any());
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
