package com.dndnamegen.namegen.admin;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only pending-submissions queue, read and write (PR 4 read + PR 6 approve/reject +
 * issue #86 pagination/bulk actions).
 * {@code /admin/**} is already {@code hasRole("ADMIN")} at the filter-chain level (see
 * {@code WebSecurityConfig}) -- no route change needed here. {@code @PreAuthorize} is the same
 * belt-and-braces second check every other admin/mutating controller in this codebase carries.
 *
 * <p>Plain server-rendered table, not htmx -- this is a low-traffic operator screen, not the
 * public browse page, so there's no product reason to match its fragment-swap interaction model.
 * Each action is a plain form POST that redirects back to {@code GET /admin/submissions} (PRG),
 * so a page refresh after an action never re-submits it.
 */
@Controller
public class AdminSubmissionController {

    private static final String REDIRECT_TO_SUBMISSIONS = "redirect:/admin/submissions";

    private final AdminSubmissionService adminSubmissionService;

    public AdminSubmissionController(AdminSubmissionService adminSubmissionService) {
        this.adminSubmissionService = adminSubmissionService;
    }

    @GetMapping("/admin/submissions")
    @PreAuthorize("hasRole('ADMIN')")
    public String submissions(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("submissionsPage", adminSubmissionService.listPendingSubmissions(page));
        return "admin/submissions";
    }

    /**
     * Admin approve: inserts the name as USER_SUBMITTED/ACTIVE, marks submission APPROVED, and
     * redirects back to the queue (PRG).
     */
    @PostMapping("/admin/submissions/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public String approve(@PathVariable Long id, Authentication authentication) {
        Long reviewerOwnerId = extractOwnerId(authentication);
        requireFlipped(adminSubmissionService.approve(id, reviewerOwnerId), id);
        return REDIRECT_TO_SUBMISSIONS;
    }

    /**
     * Admin reject: marks submission REJECTED without inserting the name, and redirects back
     * to the queue (PRG).
     */
    @PostMapping("/admin/submissions/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public String reject(@PathVariable Long id, Authentication authentication) {
        Long reviewerOwnerId = extractOwnerId(authentication);
        requireFlipped(adminSubmissionService.reject(id, reviewerOwnerId), id);
        return REDIRECT_TO_SUBMISSIONS;
    }

    /**
     * Bulk approve/reject (issue #86): one POST for a checked set of ids, keyed off which submit
     * button was clicked ({@code action=approve|reject}) rather than two near-identical endpoints
     * -- {@code admin/submissions.html}'s checkboxes are wired via the HTML5 {@code form}
     * attribute to one shared {@code <form>} outside the table (nested {@code <form>}s aren't
     * valid HTML), so both buttons already post here with the same {@code ids} list. Best-effort
     * (see {@code AdminSubmissionService.bulkApprove}/{@code bulkReject}'s Javadoc): unlike the
     * single-id actions, an already-resolved id in the batch does not 404 the whole request --
     * that would defeat the point of a bulk action for exactly the race condition it exists to
     * tolerate (another admin, or a stale checkbox from before a page reload).
     */
    @PostMapping("/admin/submissions/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public String bulkAction(
            @RequestParam(required = false) List<Long> ids, @RequestParam String action, Authentication authentication) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no submissions selected");
        }
        Long reviewerOwnerId = extractOwnerId(authentication);
        switch (action) {
            case "approve" -> adminSubmissionService.bulkApprove(ids, reviewerOwnerId);
            case "reject" -> adminSubmissionService.bulkReject(ids, reviewerOwnerId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be approve or reject");
        }
        return REDIRECT_TO_SUBMISSIONS;
    }

    private void requireFlipped(boolean flipped, Long id) {
        if (!flipped) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No submission with id " + id);
        }
    }

    private Long extractOwnerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof com.dndnamegen.namegen.user.AppUserDetails principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return principal.getOwnerId();
    }
}
