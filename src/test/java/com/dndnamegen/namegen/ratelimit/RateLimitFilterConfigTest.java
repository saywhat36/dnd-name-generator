package com.dndnamegen.namegen.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * Constructs the {@code @Bean} method directly rather than loading a Spring context -- the
 * factory method's logic (url pattern, order, filter construction) doesn't need a container to
 * exercise, matching the "construct directly" precedent {@code RateLimitFilterTest} and {@code
 * SessionIdFilterTest} already use for this package.
 */
class RateLimitFilterConfigTest {

    private final RateLimitFilterConfig config = new RateLimitFilterConfig();

    @Test
    void submissionsRateLimitFilter_should_ScopeToSubmissionsUrlOnly_When_Constructed() {
        FilterRegistrationBean<RateLimitFilter> registration =
                config.submissionsRateLimitFilter(5, Duration.ofMinutes(1), Duration.ofMinutes(10));

        assertThat(registration.getUrlPatterns()).containsExactly("/submissions");
    }

    /**
     * Must run after Spring Security's own filter chain (registered at {@code
     * SecurityProperties.DEFAULT_FILTER_ORDER}), not at the unset default of
     * {@code Ordered.LOWEST_PRECEDENCE} left implicit -- see RateLimitFilter's Javadoc for why
     * the ordering matters (SecurityContextHolder must already be populated).
     */
    @Test
    void submissionsRateLimitFilter_should_RunAfterSpringSecurityFilterChain_When_Constructed() {
        FilterRegistrationBean<RateLimitFilter> registration =
                config.submissionsRateLimitFilter(5, Duration.ofMinutes(1), Duration.ofMinutes(10));

        assertThat(registration.getOrder()).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        assertThat(registration.getOrder()).isGreaterThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void submissionsRateLimitFilter_should_WrapARateLimitFilterInstance_When_Constructed() {
        FilterRegistrationBean<RateLimitFilter> registration =
                config.submissionsRateLimitFilter(5, Duration.ofMinutes(1), Duration.ofMinutes(10));

        assertThat(registration.getFilter()).isInstanceOf(RateLimitFilter.class);
    }
}
