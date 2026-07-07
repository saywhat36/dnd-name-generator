package com.dndnamegen.namegen.ratelimit;

import com.dndnamegen.namegen.session.SessionIdFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-session token-bucket rate limiter, keyed on {@link SessionIdFilter#REQUEST_ATTRIBUTE}
 * and backed by a Caffeine cache with a TTL. Not yet registered as a Spring bean or wired to
 * any endpoint -- see docs/DECISIONS.md for why.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final long capacity;
    private final Duration refillPeriod;
    private final Cache<String, Bucket> bucketsBySession;

    public RateLimitFilter(long capacity, Duration refillPeriod, Duration bucketTtl) {
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
        this.bucketsBySession =
                Caffeine.newBuilder().expireAfterAccess(bucketTtl).build();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String sessionId = (String) request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE);
        if (sessionId == null) {
            // SessionIdFilter (which mints this attribute) is expected to always run earlier in
            // the chain once this filter is actually wired in -- no session id to scope a limit
            // by means there's nothing safe to do here except let the request through.
            filterChain.doFilter(request, response);
            return;
        }
        Bucket bucket = bucketsBySession.get(sessionId, id -> newBucket());
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            // jakarta.servlet.http.HttpServletResponse has no SC_TOO_MANY_REQUESTS constant
            // (429 postdates the Servlet spec's status-code list) -- literal per RFC 6585.
            response.setStatus(429);
        }
    }

    private Bucket newBucket() {
        Bandwidth limit =
                Bandwidth.builder().capacity(capacity).refillGreedy(capacity, refillPeriod).build();
        return Bucket.builder().addLimit(limit).build();
    }
}
