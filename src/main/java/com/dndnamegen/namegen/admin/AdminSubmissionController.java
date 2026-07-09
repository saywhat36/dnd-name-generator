package com.dndnamegen.namegen.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only pending-submissions queue, read and write (PR 4 read + PR 6 approve/reject).
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
    public String submissions(Model model) {
        model.addAttribute("submissions", adminSubmissionService.listPendingSubmissions());
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
