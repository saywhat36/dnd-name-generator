package com.dndnamegen.namegen.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.config.WebSecurityConfig;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameStatus;
import com.dndnamegen.namegen.user.AppUserDetails;
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
 * Explicit {@code @Import(WebSecurityConfig.class)} -- unlike every other {@code @WebMvcTest} in
 * this codebase, this is the first slice test that needs to distinguish role-based authorization
 * (hasRole("ADMIN")) from plain authentication, and relying on whatever {@code @WebMvcTest}
 * happens to auto-detect isn't reliable for that distinction (an unauthenticated-vs-authenticated
 * check can't tell the two apart, which is why no earlier test needed this import).
 */
@WebMvcTest(AdminReportController.class)
@Import(WebSecurityConfig.class)
class AdminReportControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AdminReportService adminReportService;

    @MockBean private NameService nameService;

    private static MockHttpServletRequestBuilder withRole(MockHttpServletRequestBuilder builder, String role) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return builder.with(csrf()).with(user(principal));
    }

    @Test
    void reports_should_Return403_When_UserRole() throws Exception {
        mockMvc.perform(withRole(get("/admin/reports"), "USER")).andExpect(status().isForbidden());
    }

    @Test
    void reports_should_Render_When_AdminRole() throws Exception {
        // Return one populated row so the th:each body actually renders -- exercises the
        // record-accessor reads, the #strings.listJoin, and the action-form URL building that an
        // empty list would leave untested.
        when(adminReportService.listReportedNames())
                .thenReturn(List.of(new ReportedNameView(
                        7L, "Grishnakh", NameStatus.FLAGGED, 4L, List.of("slur", "harassment"))));

        mockMvc.perform(withRole(get("/admin/reports"), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Grishnakh")))
                .andExpect(content().string(containsString("slur; harassment")))
                .andExpect(content().string(containsString("/admin/names/7/flag")))
                .andExpect(content().string(containsString("/admin/names/7/reject")))
                .andExpect(content().string(containsString("/admin/names/7/unflag")));
    }

    @Test
    void flag_should_SetStatusFlagged_When_Admin() throws Exception {
        when(nameService.flagName(1L)).thenReturn(true);

        mockMvc.perform(withRole(post("/admin/names/1/flag"), "ADMIN")).andExpect(status().is3xxRedirection());
    }
}
