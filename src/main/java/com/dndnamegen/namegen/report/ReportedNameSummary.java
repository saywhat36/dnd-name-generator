package com.dndnamegen.namegen.report;

import com.dndnamegen.namegen.name.NameStatus;

/** Spring Data projection for {@link NameReportRepository#findReportedNameSummaries()}. */
public interface ReportedNameSummary {

    Long getNameId();

    String getDisplayName();

    NameStatus getStatus();

    long getReportCount();
}
