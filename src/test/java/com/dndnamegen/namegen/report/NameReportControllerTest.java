package com.dndnamegen.namegen.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.NameRepository;
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

@WebMvcTest(NameReportController.class)
class NameReportControllerTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final Identity IDENTITY = Identity.of(42L, SESSION_ID);

    @Autowired private MockMvc mockMvc;

    @MockBean private NameReportService nameReportService;

    @MockBean private NameRepository nameRepository;

    /**
     * @WebMvcTest auto-registers Filter beans, so the real SessionIdFilter runs in this
     * slice. Supplying a cookie in the filter's own format (a valid UUID) makes it pass this
     * session id through instead of minting its own random one -- see the regression fixed in
     * PR #37's FavoriteControllerTest for why setting the request attribute directly isn't
     * enough.
     */
    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        return builder.cookie(new Cookie(SessionIdFilter.COOKIE_NAME, SESSION_ID)).with(csrf());
    }

    /**
     * Authenticated on top of the session cookie -- reports require an authenticated request
     * unconditionally (no anonymous fallback, see docs/DECISIONS.md, identity resolution slice
     * revision); as of slice 6 the row itself is keyed on ownerId, not sessionId.
     */
    private static MockHttpServletRequestBuilder withOwner(MockHttpServletRequestBuilder builder) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return withSession(builder).with(user(principal));
    }

    @Test
    void reportName_should_ReturnCreated_When_NameExists() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withOwner(post("/reports").param("nameId", "1").param("reason", "not a real name")))
                .andExpect(status().isCreated());

        verify(nameReportService).reportName(IDENTITY, 1L, "not a real name");
    }

    @Test
    void reportName_should_ReturnCreated_When_ReasonIsOmitted() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withOwner(post("/reports").param("nameId", "1"))).andExpect(status().isCreated());

        verify(nameReportService).reportName(IDENTITY, 1L, null);
    }

    @Test
    void reportName_should_ReturnNotFound_When_NameIdDoesNotReferenceARealName() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(withOwner(post("/reports").param("nameId", "1"))).andExpect(status().isNotFound());
    }

    @Test
    void reportName_should_TreatReasonAsNull_When_ReasonIsBlank() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withOwner(post("/reports").param("nameId", "1").param("reason", "   ")))
                .andExpect(status().isCreated());

        verify(nameReportService).reportName(IDENTITY, 1L, null);
    }

    /**
     * Regression test: an over-length reason must be rejected as a 400 here, not passed
     * through to NameReportService.saveNew -- a DB-level "value too long" violation there would
     * be misinterpreted by the DataIntegrityViolationException catch block as a concurrent-
     * report race instead of an input validation failure.
     */
    @Test
    void reportName_should_ReturnBadRequest_When_ReasonExceedsMaxLength() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);
        String tooLong = "x".repeat(257);

        mockMvc.perform(withOwner(post("/reports").param("nameId", "1").param("reason", tooLong)))
                .andExpect(status().isBadRequest());

        verify(nameReportService, never()).reportName(any(), any(), any());
    }

    /**
     * As of slice 7 (see docs/DECISIONS.md, WebSecurityConfig), POST /reports requires
     * authentication at the filter-chain level -- see FavoriteControllerTest's equivalent test
     * for why an anonymous, non-htmx request still lands on a 3xx redirect to /login.
     */
    @Test
    void reportName_should_RedirectToLogin_When_Unauthenticated() throws Exception {
        mockMvc.perform(withSession(post("/reports").param("nameId", "1"))).andExpect(status().is3xxRedirection());
    }
}
