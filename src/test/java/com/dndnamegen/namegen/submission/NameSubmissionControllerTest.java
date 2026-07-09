package com.dndnamegen.namegen.submission;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.session.SessionIdFilter;
import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(NameSubmissionController.class)
class NameSubmissionControllerTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final Identity IDENTITY = Identity.of(42L, SESSION_ID);

    @Autowired private MockMvc mockMvc;

    @MockBean private NameSubmissionService nameSubmissionService;

    /**
     * @WebMvcTest auto-registers Filter beans, so the real SessionIdFilter runs in this slice.
     * Supplying a cookie in the filter's own format (a valid UUID) makes it pass this session id
     * through instead of minting its own -- see NameReportControllerTest for the same setup.
     */
    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        return builder.cookie(new Cookie(SessionIdFilter.COOKIE_NAME, SESSION_ID)).with(csrf());
    }

    private static MockHttpServletRequestBuilder withOwner(MockHttpServletRequestBuilder builder) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return withSession(builder).with(user(principal));
    }

    @Test
    void submitName_should_ReturnCreated_When_RequestIsValid() throws Exception {
        mockMvc.perform(withOwner(post("/submissions")
                        .param("race", "ELF")
                        .param("gender", "MASCULINE")
                        .param("displayName", "Aelar")))
                .andExpect(status().isCreated());

        verify(nameSubmissionService).submit(IDENTITY, Race.ELF, Gender.MASCULINE, "Aelar");
    }

    @Test
    void submitName_should_TrimDisplayName_When_Padded() throws Exception {
        mockMvc.perform(withOwner(post("/submissions")
                        .param("race", "ELF")
                        .param("gender", "MASCULINE")
                        .param("displayName", "  Aelar  ")))
                .andExpect(status().isCreated());

        verify(nameSubmissionService).submit(IDENTITY, Race.ELF, Gender.MASCULINE, "Aelar");
    }

    @Test
    void submitName_should_ReturnBadRequest_When_DisplayNameIsBlank() throws Exception {
        mockMvc.perform(withOwner(post("/submissions")
                        .param("race", "ELF")
                        .param("gender", "MASCULINE")
                        .param("displayName", "   ")))
                .andExpect(status().isBadRequest());

        verify(nameSubmissionService, never()).submit(any(), any(), any(), any());
    }

    @Test
    void submitName_should_ReturnBadRequest_When_DisplayNameExceedsMaxLength() throws Exception {
        String tooLong = "x".repeat(129);

        mockMvc.perform(withOwner(post("/submissions")
                        .param("race", "ELF")
                        .param("gender", "MASCULINE")
                        .param("displayName", tooLong)))
                .andExpect(status().isBadRequest());

        verify(nameSubmissionService, never()).submit(any(), any(), any(), any());
    }

    /**
     * POST /submissions requires authentication at the filter-chain level (see WebSecurityConfig) --
     * an anonymous, non-htmx request lands on a 3xx redirect to /login, matching
     * NameReportControllerTest's equivalent case.
     */
    @Test
    void submitName_should_RedirectToLogin_When_Unauthenticated() throws Exception {
        mockMvc.perform(withSession(post("/submissions")
                        .param("race", "ELF")
                        .param("gender", "MASCULINE")
                        .param("displayName", "Aelar")))
                .andExpect(status().is3xxRedirection());
    }
}
