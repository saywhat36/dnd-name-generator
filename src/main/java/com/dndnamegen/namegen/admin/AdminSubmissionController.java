package com.dndnamegen.namegen.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Admin-only pending-submissions queue, read side (PR 4). {@code /admin/**} is already {@code
 * hasRole("ADMIN")} at the filter-chain level (see {@code WebSecurityConfig}) -- no route change
 * needed here, the same way {@code AdminReportController} needed none. {@code @PreAuthorize} is
 * the same belt-and-braces second check every other admin/mutating controller in this codebase
 * carries. Approve/reject actions land in a later PR; this is read-only.
 */
@Controller
public class AdminSubmissionController {

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
}
