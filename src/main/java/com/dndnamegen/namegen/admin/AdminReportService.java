package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.report.NameReportRepository;
import com.dndnamegen.namegen.report.ReportedNameSummary;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Read side of the admin reports screen (slice 9). The status flip itself lives on {@code
 * NameService} (flagName/rejectName/unflagName) -- this service only assembles the listing, it
 * doesn't own the manual-review action.
 */
@Service
public class AdminReportService {

    /** Enough to give an admin a taste of why without rendering every single report row. */
    private static final int REASON_SAMPLE_LIMIT = 3;

    private final NameReportRepository nameReportRepository;

    public AdminReportService(NameReportRepository nameReportRepository) {
        this.nameReportRepository = nameReportRepository;
    }

    public List<ReportedNameView> listReportedNames() {
        return nameReportRepository.findReportedNameSummaries().stream()
                .map(summary -> new ReportedNameView(
                        summary.getNameId(),
                        summary.getDisplayName(),
                        summary.getStatus(),
                        summary.getReportCount(),
                        nameReportRepository.findReasonSamples(
                                summary.getNameId(), PageRequest.of(0, REASON_SAMPLE_LIMIT))))
                .toList();
    }
}
