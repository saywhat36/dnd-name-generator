package com.dndnamegen.namegen.admin;

import static org.hamcrest.Matchers.containsString;
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
        when(adminSubmissionService.listPendingSubmissions())
                .thenReturn(List.of(new PendingSubmissionView(
                        7L, "Grishnakh", Race.HALF_ORC, Gender.MASCULINE, "gandalf", Instant.parse("2026-07-09T12:00:00Z"))));

        mockMvc.perform(withRole(get("/admin/submissions"), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Grishnakh")))
                .andExpect(content().string(containsString("gandalf")))
                .andExpect(content().string(containsString("/admin/submissions/7/approve")))
                .andExpect(content().string(containsString("/admin/submissions/7/reject")));
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
}
