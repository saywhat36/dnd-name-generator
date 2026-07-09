package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import java.time.Instant;

/**
 * Spring Data projection for {@link
 * NameSubmissionRepository#findPendingSummaries(org.springframework.data.domain.Pageable)}.
 */
public interface PendingSubmissionSummary {

    Long getSubmissionId();

    String getDisplayName();

    Race getRace();

    Gender getGender();

    String getSubmitterUsername();

    Instant getCreatedAt();
}
