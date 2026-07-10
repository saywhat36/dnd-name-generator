package com.dndnamegen.namegen.ratelimit;

import com.dndnamegen.namegen.user.AppUserDetails;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-owner token-bucket rate limiter, backed by a Caffeine cache with a TTL. Wired to {@code
 * POST /submissions} (issue #87) via {@link RateLimitFilterConfig} -- not a blanket {@code
 * @Component}, since Spring Boot auto-registers any {@code Filter} bean against {@code /*} by
 * default, which would apply this to every route including plain name-serving reads
 * (see docs/ARCHITECTURE.md, "Rate limiting").
 *
 * <p>Keyed on the authenticated owner id ({@link AppUserDetails#getOwnerId()}), not {@code
 * SessionIdFilter}'s session id -- the keying choice docs/DECISIONS.md explicitly deferred to
 * this wiring step. Every route this filter is registered against requires authentication
 * (enforced by Spring Security's own filter chain, which -- at its default order -100 --
 * always runs before a plain {@code FilterRegistrationBean}-registered filter with no explicit
 * order, this one included), so an owner id is reliably available by the time this filter runs,
 * and unlike a session id cookie, it can't be rotated per-request by a client that stays logged
 * in, which is exactly the bypass a session-keyed limiter would have on an authenticated-only
 * route.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final long capacity;
    private final Duration refillPeriod;
    private final Cache<Long, Bucket> bucketsByOwner;

    public RateLimitFilter(long capacity, Duration refillPeriod, Duration bucketTtl) {
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
        this.bucketsByOwner =
                Caffeine.newBuilder().expireAfterAccess(bucketTtl).build();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Long ownerId = resolveOwnerId();
        if (ownerId == null) {
            // Every route this filter is registered against already requires authentication at
            // the filter-chain level, so this shouldn't happen in practice -- fails open (same
            // as the original session-keyed version) rather than blocking a request there is
            // nothing safe or meaningful to rate-limit by.
            filterChain.doFilter(request, response);
            return;
        }
        Bucket bucket = bucketsByOwner.get(ownerId, id -> newBucket());
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            // jakarta.servlet.http.HttpServletResponse has no SC_TOO_MANY_REQUESTS constant
            // (429 postdates the Servlet spec's status-code list) -- literal per RFC 6585.
            response.setStatus(429);
        }
    }

    private Long resolveOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails principal)) {
            return null;
        }
        return principal.getOwnerId();
    }

    private Bucket newBucket() {
        Bandwidth limit =
                Bandwidth.builder().capacity(capacity).refillGreedy(capacity, refillPeriod).build();
        return Bucket.builder().addLimit(limit).build();
    }
}
