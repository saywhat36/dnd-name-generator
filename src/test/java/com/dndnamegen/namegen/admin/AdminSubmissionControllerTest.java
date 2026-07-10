package com.dndnamegen.namegen.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.config.WebSecurityConfig;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.user.AppUserDetails;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Explicit {@code @Import(WebSecurityConfig.class)} -- same reasoning as
 * {@code AdminReportControllerTest}: this needs to distinguish role-based authorization
 * (hasRole("ADMIN")) from plain authentication, which {@code @WebMvcTest}'s auto-detection alone
 * doesn't reliably cover.
 */
@WebMvcTest(AdminSubmissionController.class)
@Import(WebSecurityConfig.class)
class AdminSubmissionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AdminSubmissionService adminSubmissionService;

    private static MockHttpServletRequestBuilder withRole(MockHttpServletRequestBuilder builder, String role) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return builder.with(csrf()).with(user(principal));
    }

    @Test
    void submissions_should_Return403_When_UserRole() throws Exception {
        mockMvc.perform(withRole(get("/admin/submissions"), "USER")).andExpect(status().isForbidden());
    }

    @Test
    void submissions_should_Render_When_AdminRole() throws Exception {
        // Return one populated row so the th:each body actually renders -- exercises the
        // record-accessor reads an empty list would leave untested.
        when(adminSubmissionService.listPendingSubmissions(0))
                .thenReturn(new PendingSubmissionsPage(
                        List.of(new PendingSubmissionView(
                                7L, "Grishnakh", Race.HALF_ORC, Gender.MASCULINE, "gandalf", Instant.parse("2026-07-09T12:00:00Z"))),
                        0,
                        1,
                        1));

        mockMvc.perform(withRole(get("/admin/submissions"), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Grishnakh")))
                .andExpect(content().string(containsString("gandalf")))
                .andExpect(content().string(containsString("/admin/submissions/7/approve")))
                .andExpect(content().string(containsString("/admin/submissions/7/reject")));
    }

    /**
     * The page query param is 0-indexed and defaults to 0 when omitted -- proven here by
     * verifying the mocked service is called with page 2, not 0, when ?page=2 is passed.
     */
    @Test
    void submissions_should_PassThroughRequestedPage_When_PageParamIsGiven() throws Exception {
        when(adminSubmissionService.listPendingSubmissions(2)).thenReturn(new PendingSubmissionsPage(List.of(), 2, 3, 0));

        mockMvc.perform(withRole(get("/admin/submissions").param("page", "2"), "ADMIN")).andExpect(status().isOk());

        verify(adminSubmissionService).listPendingSubmissions(2);
    }

    @Test
    void approve_should_Return403_When_UserRole() throws Exception {
        mockMvc.perform(withRole(post("/admin/submissions/1/approve"), "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_should_RedirectToSubmissions_When_AdminAndSubmissionFound() throws Exception {
        when(adminSubmissionService.approve(7L, 42L)).thenReturn(true);

        mockMvc.perform(withRole(post("/admin/submissions/7/approve"), "ADMIN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/submissions"));
    }

    @Test
    void approve_should_Return404_When_SubmissionNotFound() throws Exception {
        when(adminSubmissionService.approve(999L, 42L)).thenReturn(false);

        mockMvc.perform(withRole(post("/admin/submissions/999/approve"), "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reject_should_Return403_When_UserRole() throws Exception {
        mockMvc.perform(withRole(post("/admin/submissions/1/reject"), "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_should_RedirectToSubmissions_When_AdminAndSubmissionFound() throws Exception {
        when(adminSubmissionService.reject(7L, 42L)).thenReturn(true);

        mockMvc.perform(withRole(post("/admin/submissions/7/reject"), "ADMIN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/submissions"));
    }

    @Test
    void reject_should_Return404_When_SubmissionNotFound() throws Exception {
        when(adminSubmissionService.reject(999L, 42L)).thenReturn(false);

        mockMvc.perform(withRole(post("/admin/submissions/999/reject"), "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bulkAction_should_Return403_When_UserRole() throws Exception {
        mockMvc.perform(withRole(
                        post("/admin/submissions/bulk").param("ids", "1", "2").param("action", "approve"), "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bulkAction_should_CallBulkApproveAndRedirect_When_ActionIsApprove() throws Exception {
        mockMvc.perform(withRole(
                        post("/admin/submissions/bulk").param("ids", "1", "2").param("action", "approve"), "ADMIN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/submissions"));

        verify(adminSubmissionService).bulkApprove(eq(List.of(1L, 2L)), eq(42L));
    }

    @Test
    void bulkAction_should_CallBulkRejectAndRedirect_When_ActionIsReject() throws Exception {
        mockMvc.perform(withRole(
                        post("/admin/submissions/bulk").param("ids", "1", "2").param("action", "reject"), "ADMIN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/submissions"));

        verify(adminSubmissionService).bulkReject(eq(List.of(1L, 2L)), eq(42L));
    }

    @Test
    void bulkAction_should_ReturnBadRequest_When_NoIdsSelected() throws Exception {
        mockMvc.perform(withRole(post("/admin/submissions/bulk").param("action", "approve"), "ADMIN"))
                .andExpect(status().isBadRequest());

        verify(adminSubmissionService, never()).bulkApprove(any(), any());
    }

    @Test
    void bulkAction_should_ReturnBadRequest_When_ActionIsInvalid() throws Exception {
        mockMvc.perform(withRole(
                        post("/admin/submissions/bulk").param("ids", "1").param("action", "unflag"), "ADMIN"))
                .andExpect(status().isBadRequest());

        verify(adminSubmissionService, never()).bulkApprove(any(), any());
        verify(adminSubmissionService, never()).bulkReject(any(), any());
    }
}
