package com.dndnamegen.namegen.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionIdFilterTest {

    private final SessionIdFilter filter = new SessionIdFilter(true);

    @Test
    void doFilterInternal_should_MintCookie_When_NoneIsPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        Cookie issued = response.getCookie(SessionIdFilter.COOKIE_NAME);
        assertThat(issued).isNotNull();
        assertThat(issued.getValue()).isNotBlank();
        assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.getPath()).isEqualTo("/");
        assertThat(issued.getMaxAge()).isEqualTo((int) Duration.ofDays(365).toSeconds());
        assertThat(request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(issued.getValue());
        assertThat(response.getHeader("Set-Cookie"))
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_should_ReuseExistingCookie_When_OneIsPresent() throws Exception {
        String existingSessionId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionIdFilter.COOKIE_NAME, existingSessionId));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getCookie(SessionIdFilter.COOKIE_NAME)).isNull();
        assertThat(request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(existingSessionId);
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_should_MintCookie_When_ExistingValueIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionIdFilter.COOKIE_NAME, ""));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        Cookie issued = response.getCookie(SessionIdFilter.COOKIE_NAME);
        assertThat(issued).isNotNull();
        assertThat(issued.getValue()).isNotBlank();
        assertThat(request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(issued.getValue());
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_should_MintCookie_When_ExistingValueIsNotAUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionIdFilter.COOKIE_NAME, "some-shared-string"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        Cookie issued = response.getCookie(SessionIdFilter.COOKIE_NAME);
        assertThat(issued).isNotNull();
        assertThat(issued.getValue()).isNotEqualTo("some-shared-string");
        assertThat(request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(issued.getValue());
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_should_MintNonSecureCookie_When_SecureCookieDisabled() throws Exception {
        SessionIdFilter nonSecureFilter = new SessionIdFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        nonSecureFilter.doFilterInternal(request, response, chain);

        assertThat(response.getCookie(SessionIdFilter.COOKIE_NAME).getSecure()).isFalse();
        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Secure");
        verify(chain).doFilter(request, response);
    }
}
