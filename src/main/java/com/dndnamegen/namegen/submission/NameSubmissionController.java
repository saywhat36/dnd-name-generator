package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.submission.dto.SubmissionResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NameSubmissionController {

    /**
     * Matches name_submissions.display_name's VARCHAR(128) column. Defense-in-depth, not the
     * primary length guard: under default config, QualityGateService.passesQualityGate already
     * rejects anything over app.quality-gate.max-length (30 by default) with a 400 before this is
     * reached, so the DataIntegrityViolationException-misread-as-409 scenario that motivates
     * NameReportController.MAX_REASON_LENGTH (whose reason param bypasses the quality gate
     * entirely) only actually applies here if max-length is ever configured above 128.
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

    /**
     * Read-only, owner-keyed -- mirrors {@code FavoriteController.listFavorites}'s shape
     * (service returns entities, controller maps to response DTOs). Deliberately no way to
     * retract a submission from this view (see docs/DECISIONS.md): a genuine product decision,
     * not an oversight -- reports never got a self-view either, for the same reason.
     */
    @GetMapping("/submissions/mine")
    @PreAuthorize("isAuthenticated()")
    public List<SubmissionResponse> listMySubmissions(Identity identity) {
        return nameSubmissionService.listMySubmissions(identity).stream()
                .map(SubmissionResponse::from)
                .toList();
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
