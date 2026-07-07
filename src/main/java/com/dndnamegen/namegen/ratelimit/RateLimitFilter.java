package com.dndnamegen.namegen.ratelimit;

import com.dndnamegen.namegen.session.SessionIdFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-session token-bucket rate limiter, keyed on {@link SessionIdFilter#REQUEST_ATTRIBUTE}
 * and backed by a Caffeine cache with a TTL (sessions are just cookies -- nothing else
 * naturally evicts them, so an unbounded map would grow forever).
 *
 * <p><strong>Not registered as a Spring bean, and not applied to any endpoint, in Phase 1.</strong>
 * Per docs/ARCHITECTURE.md's "Rate limiting" section: standard name-serving requests never
 * call the LLM synchronously (replenishment is async, off the request path), so there is
 * nothing on the current request path for a rate limiter to protect -- applying one anyway
 * would just penalize a cheap DB read for no reason. This class exists as ready-to-wire
 * scaffolding for Phase 3's backstory endpoint, the first synchronous LLM call on a request
 * path in this project. Deliberately has no {@code @Component} annotation: Spring Boot's
 * embedded servlet container auto-registers every {@code Filter} bean against {@code /*} by
 * default (the same auto-registration behavior that silently broke an earlier
 * {@code FavoriteControllerTest} assumption -- see docs/DECISIONS.md), so annotating this
 * class now would make it intercept every request today, contradicting "not applied to
 * name-serving." Phase 3 adds the {@code @Component} (or an explicit
 * {@code FilterRegistrationBean} scoped to the backstory URL pattern) at the point it's
 * actually wired to a real endpoint.
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
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, refillPeriod));
        return Bucket.builder().addLimit(limit).build();
    }
}
