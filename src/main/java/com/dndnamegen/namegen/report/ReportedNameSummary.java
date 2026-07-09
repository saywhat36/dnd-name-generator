package com.dndnamegen.namegen.report;

import com.dndnamegen.namegen.name.NameStatus;

/**
 * Spring Data projection for {@link
 * NameReportRepository#findReportedNameSummaries(org.springframework.data.domain.Pageable)}.
 */
public interface ReportedNameSummary {

    Long getNameId();

    String getDisplayName();

    NameStatus getStatus();

    long getReportCount();
}
