package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.submission.NameSubmission;
import com.dndnamegen.namegen.submission.NameSubmissionRepository;
import com.dndnamegen.namegen.submission.PendingSubmissionSummary;
import com.dndnamegen.namegen.submission.SubmissionInsertDao;
import com.dndnamegen.namegen.submission.SubmissionStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin moderation for pending submissions (PR 4 read + PR 6 approve/reject + issue #86
 * pagination/bulk actions). See PR 4 comment for list-side rationale.
 */
@Service
public class AdminSubmissionService {

    /**
     * Rows per page. Was a hard {@code MAX_PENDING} cap (200) before issue #86 -- a queue that
     * outgrew it simply had its tail invisible, with no way to reach it. Now a per-page size:
     * {@link #listPendingSubmissions(int)} paginates instead of truncating, so no submission is
     * ever unreachable no matter how large the backlog grows. 50 keeps a page comfortably
     * readable on the plain server-rendered table this screen already uses.
     */
    private static final int PAGE_SIZE = 50;

    private final NameSubmissionRepository nameSubmissionRepository;
    private final SubmissionInsertDao submissionInsertDao;

    public AdminSubmissionService(
            NameSubmissionRepository nameSubmissionRepository, SubmissionInsertDao submissionInsertDao) {
        this.nameSubmissionRepository = nameSubmissionRepository;
        this.submissionInsertDao = submissionInsertDao;
    }

    /**
     * @param page 0-indexed. Out-of-range pages (negative, or past the last page) are not
     *     special-cased here -- {@code Pageable}/Spring Data handles a negative page by throwing
     *     and a too-high page by returning an empty content list, which the template already
     *     renders as "no pending submissions" without needing a dedicated branch.
     */
    public PendingSubmissionsPage listPendingSubmissions(int page) {
        Page<PendingSubmissionSummary> result =
                nameSubmissionRepository.findPendingSummaries(SubmissionStatus.PENDING, PageRequest.of(page, PAGE_SIZE));

        List<PendingSubmissionView> views = result.getContent().stream()
                .map(summary -> new PendingSubmissionView(
                        summary.getSubmissionId(),
                        summary.getDisplayName(),
                        summary.getRace(),
                        summary.getGender(),
                        summary.getSubmitterUsername(),
                        summary.getCreatedAt()))
                .toList();

        return new PendingSubmissionsPage(views, page, result.getTotalPages(), result.getTotalElements());
    }

    /**
     * Admin approve: loads the submission (404 if missing/not PENDING), inserts it as a
     * USER_SUBMITTED/ACTIVE name via ON CONFLICT (idempotent -- if the name already exists,
     * insert returns 0 but we still mark the submission APPROVED), and records the reviewer's
     * decision + timestamp. Even if the name already existed, the submission is marked APPROVED
     * -- the reviewer's action is the atomic unit being recorded, not whether the name is new.
     *
     * <p>Returns true if the submission was approved (found and flipped); false if not found or
     * not PENDING (404 mapping).
     */
    @Transactional
    public boolean approve(Long submissionId, Long reviewerOwnerId) {
        NameSubmission submission = nameSubmissionRepository.findById(submissionId).orElse(null);
        if (submission == null || submission.getStatus() != SubmissionStatus.PENDING) {
            return false;
        }

        // Insert the name (idempotent via ON CONFLICT). Return value tells us if a new row was
        // created, but the submission is approved regardless.
        submissionInsertDao.insertSubmitted(submission.getDisplayName(), submission.getRace(), submission.getGender());

        // Record the approval decision.
        nameSubmissionRepository.updateStatus(
                submissionId, SubmissionStatus.APPROVED, reviewerOwnerId, Instant.now());

        return true;
    }

    /**
     * Admin reject: marks the submission REJECTED and records the reviewer's decision + timestamp.
     * No name insert happens.
     *
     * <p>Returns true if the submission was rejected (found and flipped); false if not found or
     * not PENDING (404 mapping).
     */
    @Transactional
    public boolean reject(Long submissionId, Long reviewerOwnerId) {
        NameSubmission submission = nameSubmissionRepository.findById(submissionId).orElse(null);
        if (submission == null || submission.getStatus() != SubmissionStatus.PENDING) {
            return false;
        }

        nameSubmissionRepository.updateStatus(
                submissionId, SubmissionStatus.REJECTED, reviewerOwnerId, Instant.now());

        return true;
    }

    /**
     * Bulk approve (issue #86): applies {@link #approve(Long, Long)} to every id in the batch,
     * inside one transaction. Best-effort, not all-or-nothing -- an id that's already resolved
     * (a race with another admin, or a stale checked box from before a page reload) is silently
     * skipped rather than failing the whole batch, matching the single-submission {@code
     * approve}'s own "missing/not PENDING -> false, no exception" contract. A triage screen where
     * one stale row aborts 49 valid approvals would be worse than the race it's guarding against.
     */
    @Transactional
    public void bulkApprove(List<Long> submissionIds, Long reviewerOwnerId) {
        submissionIds.forEach(id -> approve(id, reviewerOwnerId));
    }

    /** Bulk reject (issue #86): same best-effort contract as {@link #bulkApprove}. */
    @Transactional
    public void bulkReject(List<Long> submissionIds, Long reviewerOwnerId) {
        submissionIds.forEach(id -> reject(id, reviewerOwnerId));
    }
}
