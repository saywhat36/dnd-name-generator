package com.dndnamegen.namegen.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /**
     * SecurityContextHolder is thread-local static state -- must be cleared after every test so
     * one test's authenticated owner doesn't leak into the next (they'd otherwise share a bucket
     * by accident, or a "no authentication" test would see a stale principal from a prior test
     * run on the same thread).
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_should_AllowRequest_When_BucketHasTokensRemaining() throws Exception {
        authenticateAs(1L);
        RateLimitFilter filter = new RateLimitFilter(2, REFILL_PERIOD, BUCKET_TTL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doFilterInternal_should_Return429AndSkipChain_When_BucketIsExhausted() throws Exception {
        authenticateAs(2L);
        RateLimitFilter filter = new RateLimitFilter(1, REFILL_PERIOD, BUCKET_TTL);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilterInternal(new MockHttpServletRequest(), secondResponse, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }

    @Test
    void doFilterInternal_should_TrackSeparateBuckets_When_OwnersDiffer() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, REFILL_PERIOD, BUCKET_TTL);
        FilterChain chain = mock(FilterChain.class);

        authenticateAs(3L);
        MockHttpServletResponse firstOwnerResponse = new MockHttpServletResponse();
        filter.doFilterInternal(new MockHttpServletRequest(), firstOwnerResponse, chain);

        authenticateAs(4L);
        MockHttpServletResponse secondOwnerResponse = new MockHttpServletResponse();
        filter.doFilterInternal(new MockHttpServletRequest(), secondOwnerResponse, chain);

        assertThat(firstOwnerResponse.getStatus()).isEqualTo(200);
        assertThat(secondOwnerResponse.getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(any(), any());
    }

    /**
     * Every route this filter is registered against already requires authentication at the
     * filter-chain level (see RateLimitFilterConfig/WebSecurityConfig), so an unauthenticated
     * request reaching this filter shouldn't happen in practice -- this proves the defensive
     * fail-open path still works if it somehow did, rather than throwing or blocking.
     */
    @Test
    void doFilterInternal_should_AllowRequest_When_NoAuthenticatedOwner() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, REFILL_PERIOD, BUCKET_TTL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static void authenticateAs(Long ownerId) {
        AppUserDetails principal = new AppUserDetails(ownerId, "gandalf", "hash", true, List.of());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, "hash", List.of()));
    }
}
