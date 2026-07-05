package com.dndnamegen.namegen.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionIdFilterTest {

    private final SessionIdFilter filter = new SessionIdFilter();

    @Test
    void doFilterInternal_should_MintCookie_When_NoneIsPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
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
    void doFilterInternal_should_ReuseExistingCookie_When_OneIsPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionIdFilter.COOKIE_NAME, "existing-session-id"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getCookie(SessionIdFilter.COOKIE_NAME)).isNull();
        assertThat(request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE)).isEqualTo("existing-session-id");
    }
}
