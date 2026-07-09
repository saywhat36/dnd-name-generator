package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.name.NameService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only reported-names review (slice 9). {@code /admin/**} is already {@code
 * hasRole("ADMIN")} at the filter-chain level (see {@code WebSecurityConfig}, placeholder since
 * slice 7); {@code @PreAuthorize} here is the same belt-and-braces second check every other
 * mutating controller in this codebase carries, not the primary gate.
 *
 * <p>Plain server-rendered table, not htmx -- this is a low-traffic operator screen, not the
 * public browse page, so there's no product reason to match its fragment-swap interaction model.
 * Each action is a plain form POST that redirects back to {@code GET /admin/reports} (PRG), so a
 * page refresh after an action never re-submits it.
 */
@Controller
public class AdminReportController {

    private static final String REDIRECT_TO_REPORTS = "redirect:/admin/reports";

    private final AdminReportService adminReportService;
    private final NameService nameService;

    public AdminReportController(AdminReportService adminReportService, NameService nameService) {
        this.adminReportService = adminReportService;
        this.nameService = nameService;
    }

    @GetMapping("/admin/reports")
    @PreAuthorize("hasRole('ADMIN')")
    public String reports(Model model) {
        model.addAttribute("reports", adminReportService.listReportedNames());
        return "admin/reports";
    }

    /**
     * Reuses {@link NameService#flagName(Long)} -- the manual-review status flip already exists
     * (Week 5), this endpoint is just a new, admin-only caller of it.
     */
    @PostMapping("/admin/names/{id}/flag")
    @PreAuthorize("hasRole('ADMIN')")
    public String flag(@PathVariable Long id) {
        requireFlipped(nameService.flagName(id), id);
        return REDIRECT_TO_REPORTS;
    }

    @PostMapping("/admin/names/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public String reject(@PathVariable Long id) {
        requireFlipped(nameService.rejectName(id), id);
        return REDIRECT_TO_REPORTS;
    }

    @PostMapping("/admin/names/{id}/unflag")
    @PreAuthorize("hasRole('ADMIN')")
    public String unflag(@PathVariable Long id) {
        requireFlipped(nameService.unflagName(id), id);
        return REDIRECT_TO_REPORTS;
    }

    private void requireFlipped(boolean flipped, Long id) {
        if (!flipped) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No name with id " + id);
        }
    }
}
