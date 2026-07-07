package com.dndnamegen.namegen.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dndnamegen.namegen.session.SessionIdFilter;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    @Test
    void doFilterInternal_should_AllowRequest_When_BucketHasTokensRemaining() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, REFILL_PERIOD, BUCKET_TTL);
        MockHttpServletRequest request = requestForSession("session-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doFilterInternal_should_Return429AndSkipChain_When_BucketIsExhausted() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, REFILL_PERIOD, BUCKET_TTL);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestForSession("session-b"), new MockHttpServletResponse(), chain);

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilterInternal(requestForSession("session-b"), secondResponse, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }

    @Test
    void doFilterInternal_should_TrackSeparateBuckets_When_SessionsDiffer() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, REFILL_PERIOD, BUCKET_TTL);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse firstSessionResponse = new MockHttpServletResponse();
        filter.doFilterInternal(requestForSession("session-c"), firstSessionResponse, chain);

        MockHttpServletResponse secondSessionResponse = new MockHttpServletResponse();
        filter.doFilterInternal(requestForSession("session-d"), secondSessionResponse, chain);

        assertThat(firstSessionResponse.getStatus()).isEqualTo(200);
        assertThat(secondSessionResponse.getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_should_AllowRequest_When_NoSessionIdAttributePresent() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, REFILL_PERIOD, BUCKET_TTL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest requestForSession(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionIdFilter.REQUEST_ATTRIBUTE, sessionId);
        return request;
    }
}
