package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NameSubmissionController {

    /**
     * Matches name_submissions.display_name's VARCHAR(128) column. Rejected here as a 400 rather
     * than left to the DB -- an over-length value would otherwise throw a
     * DataIntegrityViolationException that NameSubmissionService's catch block would misinterpret
     * as a concurrent-submission race (its uq_submissions_pending remap), returning a misleading
     * 409 for what is really a bad request. Same reasoning as NameReportController.MAX_REASON_LENGTH.
     */
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;

    private final NameSubmissionService nameSubmissionService;

    public NameSubmissionController(NameSubmissionService nameSubmissionService) {
        this.nameSubmissionService = nameSubmissionService;
    }

    @PostMapping("/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public void submitName(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam String displayName,
            Identity identity) {
        nameSubmissionService.submit(identity, race, gender, requireValidDisplayName(displayName));
    }

    private String requireValidDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName must not be blank");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return trimmed;
    }
}
