package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.submission.NameSubmissionRepository;
import com.dndnamegen.namegen.submission.SubmissionStatus;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Read side of the admin submissions queue (PR 4). Approve/reject actions land in a later PR;
 * this service only assembles the listing, it doesn't own the moderation decision.
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

    public AdminSubmissionService(NameSubmissionRepository nameSubmissionRepository) {
        this.nameSubmissionRepository = nameSubmissionRepository;
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
}
