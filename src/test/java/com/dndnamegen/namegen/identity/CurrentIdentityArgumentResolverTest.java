package com.dndnamegen.namegen.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.session.SessionIdFilter;
import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.NativeWebRequest;

class CurrentIdentityArgumentResolverTest {

    private final CurrentIdentityArgumentResolver resolver = new CurrentIdentityArgumentResolver();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsParameter_should_ReturnTrue_When_ParameterTypeIsIdentity() throws NoSuchMethodException {
        var parameter =
                new org.springframework.core.MethodParameter(
                        TestController.class.getMethod("handle", Identity.class), 0);

        assertThat(resolver.supportsParameter(parameter)).isTrue();
    }

    /**
     * As of slice 7 (see docs/DECISIONS.md), the browse routes are public, so this resolver
     * must tolerate an anonymous request instead of throwing -- route-level security (the
     * filter chain in WebSecurityConfig, backed by @PreAuthorize) is what actually keeps
     * anonymous requests off the favorites/reports controllers now, not this resolver.
     */
    @Test
    void resolveArgument_should_ReturnAnonymousIdentity_When_NoAuthenticationPresent() {
        NativeWebRequest webRequest = webRequestWithSessionId("session-1");

        Identity result = (Identity) resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result.isAuthenticated()).isFalse();
        assertThat(result.ownerId()).isNull();
        assertThat(result.sessionId()).isEqualTo("session-1");
    }

    @Test
    void resolveArgument_should_ReturnAnonymousIdentity_When_AuthenticationIsAnonymous() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        NativeWebRequest webRequest = webRequestWithSessionId("session-1");

        Identity result = (Identity) resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result.isAuthenticated()).isFalse();
        assertThat(result.ownerId()).isNull();
        assertThat(result.sessionId()).isEqualTo("session-1");
    }

    @Test
    void resolveArgument_should_ReturnOwnerIdentity_When_PrincipalIsAppUserDetails() {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, principal.getPassword(), principal.getAuthorities()));
        NativeWebRequest webRequest = webRequestWithSessionId("session-1");

        Identity result = (Identity) resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result.ownerId()).isEqualTo(42L);
        assertThat(result.sessionId()).isEqualTo("session-1");
    }

    private NativeWebRequest webRequestWithSessionId(String sessionId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE)).thenReturn(sessionId);
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest()).thenReturn(request);
        return webRequest;
    }

    private interface TestController {
        void handle(Identity identity);
    }
}
