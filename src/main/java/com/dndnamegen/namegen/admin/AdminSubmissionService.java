package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.submission.NameSubmission;
import com.dndnamegen.namegen.submission.NameSubmissionRepository;
import com.dndnamegen.namegen.submission.SubmissionInsertDao;
import com.dndnamegen.namegen.submission.SubmissionStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin moderation for pending submissions (PR 4 read + PR 6 approve/reject). See PR 4 comment
 * for list-side rationale.
 */
@Service
public class AdminSubmissionService {

    /**
     * Cap on pending-submission rows, matching {@code AdminReportService.MAX_REPORTED_NAMES}'s
     * reasoning: bounds this screen's query to a fixed cost no matter how large the queue grows.
     * Oldest-first ordering (see {@code NameSubmissionRepository.findPendingSummaries}) means the
     * truncated tail is the most-recently-submitted names -- acceptable for a low-traffic
     * operator triage screen.
     */
    private static final int MAX_PENDING = 200;

    private final NameSubmissionRepository nameSubmissionRepository;
    private final SubmissionInsertDao submissionInsertDao;

    public AdminSubmissionService(
            NameSubmissionRepository nameSubmissionRepository, SubmissionInsertDao submissionInsertDao) {
        this.nameSubmissionRepository = nameSubmissionRepository;
        this.submissionInsertDao = submissionInsertDao;
    }

    public List<PendingSubmissionView> listPendingSubmissions() {
        return nameSubmissionRepository
                .findPendingSummaries(SubmissionStatus.PENDING, PageRequest.of(0, MAX_PENDING))
                .stream()
                .map(summary -> new PendingSubmissionView(
                        summary.getSubmissionId(),
                        summary.getDisplayName(),
                        summary.getRace(),
                        summary.getGender(),
                        summary.getSubmitterUsername(),
                        summary.getCreatedAt()))
                .toList();
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
}
