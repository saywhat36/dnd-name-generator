package com.dndnamegen.namegen.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link RateLimitFilter}, scoped to {@code POST /submissions} only (issue #87) --
 * every other route is untouched, matching docs/ARCHITECTURE.md's "scoped deliberately, not
 * blanket" rule. Not a {@code @Component} on the filter itself: Spring Boot auto-registers any
 * bare {@code Filter} bean against {@code /*} by default, which is exactly the blanket
 * application this config avoids by using an explicit {@link FilterRegistrationBean} with
 * {@code addUrlPatterns} instead.
 *
 * <p>{@code app.rate-limit.*} follows the existing {@code app.*} config convention (see {@code
 * app.quality-gate.*}). Defaults: 5 submissions per minute per owner, buckets evicted after 10
 * minutes of inactivity -- generous enough that no genuine user hits it in normal use, tight
 * enough to blunt a scripted flood of junk submissions into the moderation queue.
 */
@Configuration
public class RateLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> submissionsRateLimitFilter(
            @Value("${app.rate-limit.submissions.capacity:5}") long capacity,
            @Value("${app.rate-limit.submissions.refill-period:1m}") Duration refillPeriod,
            @Value("${app.rate-limit.submissions.bucket-ttl:10m}") Duration bucketTtl) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(capacity, refillPeriod, bucketTtl));
        registration.setUrlPatterns(List.of("/submissions"));
        // Explicit, not relying on FilterRegistrationBean's own unset-order default
        // (Ordered.LOWEST_PRECEDENCE): must run after Spring Security's filter chain
        // (registered at SecurityProperties.DEFAULT_FILTER_ORDER, -100) so RateLimitFilter's
        // SecurityContextHolder read sees a populated, already-authenticated context -- see
        // RateLimitFilter's own Javadoc.
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
