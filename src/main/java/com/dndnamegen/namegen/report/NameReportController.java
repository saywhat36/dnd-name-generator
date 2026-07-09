package com.dndnamegen.namegen.report;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.NameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NameReportController {

    /**
     * Matches name_reports.reason's VARCHAR(256) column. Rejected here as a 400 rather than
     * left to the DB -- an over-length value would otherwise throw a DataIntegrityViolationException
     * that NameReportService.saveNew's catch block would misinterpret as a concurrent-report
     * race (its re-query would find nothing, since the insert never landed, and rethrow the
     * original exception as an unmapped 500).
     */
    private static final int MAX_REASON_LENGTH = 256;

    private final NameReportService nameReportService;
    private final NameRepository nameRepository;

    public NameReportController(NameReportService nameReportService, NameRepository nameRepository) {
        this.nameReportService = nameReportService;
        this.nameRepository = nameRepository;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public void reportName(
            @RequestParam Long nameId, @RequestParam(required = false) String reason, Identity identity) {
        requireNameExists(nameId);
        nameReportService.reportName(identity, nameId, normalizeReason(reason));
    }

    private void requireNameExists(Long nameId) {
        if (!nameRepository.existsById(nameId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No name with id " + nameId);
        }
    }

    /**
     * Blank ("" or whitespace-only) is treated the same as omitted -- otherwise "no reason
     * given" would have two different representations in the same nullable column.
     */
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return reason;
    }
}
