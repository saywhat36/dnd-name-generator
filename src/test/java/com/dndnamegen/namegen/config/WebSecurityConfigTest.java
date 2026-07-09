package com.dndnamegen.namegen.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.name.NameController;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.user.AppUserDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Proves the slice 1 CSRF wiring and the slice 7 route-level authorization/htmx-entry-point
 * wiring are genuinely on. {@code POST /names/1/flag} (picked arbitrarily -- any htmx-driven
 * POST/DELETE route not explicitly listed in {@code WebSecurityConfig} would do) falls through
 * to the {@code anyRequest().authenticated()} catch-all, so it doubles as this slice's proof
 * that unlisted routes default to authenticated rather than public.
 */
@WebMvcTest(NameController.class)
class WebSecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private NameService nameService;

    private static MockHttpServletRequestBuilder withOwner(MockHttpServletRequestBuilder builder) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return builder.with(csrf()).with(user(principal));
    }

    @Test
    void existingPostEndpoint_should_Return2xx_When_AuthenticatedWithCsrfTokenPresent() throws Exception {
        when(nameService.flagName(1L)).thenReturn(true);

        mockMvc.perform(withOwner(post("/names/1/flag"))).andExpect(status().isNoContent());
    }

    @Test
    void existingPostEndpoint_should_Return403_When_CsrfTokenMissing() throws Exception {
        mockMvc.perform(post("/names/1/flag")).andExpect(status().isForbidden());
    }

    /**
     * Slice 7: routes not explicitly listed in WebSecurityConfig default to
     * anyRequest().authenticated() rather than public -- an ordinary (non-htmx) browser request
     * with no principal gets the normal 302-to-/login behavior.
     */
    @Test
    void unlistedRoute_should_RedirectToLogin_When_AnonymousBrowserRequest() throws Exception {
        mockMvc.perform(post("/names/1/flag").with(csrf())).andExpect(status().is3xxRedirection());
    }

    /**
     * Slice 7's htmx-specific piece (HtmxAuthenticationEntryPoint): the same anonymous request,
     * but carrying the HX-Request header htmx sends on every request it issues, gets a bare 401
     * with an HX-Redirect header instead -- htmx performs a full-page navigation to /login off
     * that header rather than swapping the (401, non-HTML) response body into its target
     * element, which is what a plain 302-followed-by-htmx's-XHR would otherwise do.
     */
    @Test
    void unlistedRoute_should_Return401WithHxRedirect_When_AnonymousHtmxRequest() throws Exception {
        mockMvc.perform(post("/names/1/flag").with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
    }
}
