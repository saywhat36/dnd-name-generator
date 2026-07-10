package com.dndnamegen.namegen.admin;

import java.util.List;

/**
 * One page of the admin submissions queue -- what {@code admin/submissions.html} needs to
 * render both the rows and previous/next/total navigation (issue #86).
 */
public record PendingSubmissionsPage(
        List<PendingSubmissionView> submissions, int page, int totalPages, long totalElements) {

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page < totalPages - 1;
    }
}
