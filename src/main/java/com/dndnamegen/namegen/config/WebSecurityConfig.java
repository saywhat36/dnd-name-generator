package com.dndnamegen.namegen.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Slice 7 of the security rollout: enforce the product rule route-level rather than leaving
 * every route anonymous-accessible (slice 1's stopgap, see its own Javadoc below) -- viewing
 * (browsing names) is public, everything that writes (generating more AI names, favoriting,
 * reporting) requires login. See docs/DECISIONS.md for the full route table and the
 * public-read/authenticated-write rationale.
 *
 * <p>Still true from slice 1: the starter's default filter chain also attaches {@code
 * Cache-Control: no-cache, no-store, max-age=0, must-revalidate}, {@code Pragma: no-cache},
 * {@code X-Content-Type-Options: nosniff}, and {@code X-Frame-Options: DENY} to every response.
 * These are intentionally left as-is -- there's no static asset caching to preserve here, and
 * disallowing framing is a reasonable default this app never needed to opt out of.
 */
@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        // Spring Security 6 footgun: the CsrfToken is resolved lazily (deferred) so the
        // XSRF-TOKEN cookie is only written once something actually reads the token. The
        // default request handler expects callers to read it via a request attribute
        // populated by server-rendered forms, which this htmx-driven page never does.
        // Setting the attribute name to null forces eager resolution on every request, so
        // the cookie is always present for htmx's configRequest listener to echo back.
        requestHandler.setCsrfRequestAttributeName(null);

        http.authorizeHttpRequests(authorize -> authorize
                        // Spring Security 6's AuthorizationFilter runs on every dispatcher type,
                        // including ERROR -- without this, the container's forward to /error for
                        // an anonymous 400/404 (e.g. GET /browse with a missing required param, or
                        // any mistyped URL) falls through to the anyRequest().authenticated()
                        // catch-all below and 302s the visitor to /login instead of surfacing the
                        // real error, on pages this slice just made public. Caught in review on
                        // PR #72.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        // Viewing is public: the landing page, the htmx browse fragment, and the
                        // auth entry points themselves.
                        .requestMatchers(
                                new AntPathRequestMatcher("/", "GET"),
                                new AntPathRequestMatcher("/browse", "GET"),
                                new AntPathRequestMatcher("/login", "GET"),
                                new AntPathRequestMatcher("/register", "GET"),
                                new AntPathRequestMatcher("/register", "POST"))
                        .permitAll()
                        // health is a liveness check, safe to leave public (already unauthenticated
                        // pre-slice-7, see application.yml's management.endpoints exposure comment
                        // -- no behavior change here). metrics is deliberately NOT included here,
                        // unlike health -- it exposes the full Micrometer registry, including Spring
                        // AI's chat-client/token-usage timers/counters (operational/cost signal), to
                        // any caller. It falls through to anyRequest().authenticated() below instead.
                        // Flagged in review on PR #72; was already anonymous-accessible under slice
                        // 1's blanket permitAll() but this is the slice that establishes real authz.
                        .requestMatchers(EndpointRequest.to("health"))
                        .permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations())
                        .permitAll()
                        // Everything that mutates state requires login: the manual AI-generation
                        // trigger, favoriting/unfavoriting/listing favorites, reporting, and
                        // submitting/listing-your-own a candidate name for review.
                        // Belt-and-braces: the mutating controller methods also carry
                        // @PreAuthorize("isAuthenticated()") (see FavoriteController,
                        // NameReportController, NameSubmissionController, NameBrowserController.generateMore) --
                        // this filter chain is the primary gate, @PreAuthorize is a second,
                        // independent check that keeps rejecting direct calls even if a route
                        // matcher here is ever misconfigured.
                        .requestMatchers(
                                new AntPathRequestMatcher("/browse/generate-more", "POST"),
                                new AntPathRequestMatcher("/favorites", "POST"),
                                new AntPathRequestMatcher("/favorites", "GET"),
                                new AntPathRequestMatcher("/favorites/*", "DELETE"),
                                new AntPathRequestMatcher("/reports", "POST"),
                                new AntPathRequestMatcher("/submissions", "POST"),
                                new AntPathRequestMatcher("/submissions/mine", "GET"))
                        .authenticated()
                        // Slice 9: AdminReportController now serves this (role plumbing --
                        // users.role, DbUserDetailsService's ROLE_ mapping -- was already in
                        // place since slice 6). This rule predates the controller (was a
                        // placeholder), unchanged now that one exists.
                        .requestMatchers(new AntPathRequestMatcher("/admin/**"))
                        .hasRole("ADMIN")
                        // Everything else (POST /logout, any route not listed above) defaults to
                        // authenticated rather than public -- secure by default for routes this
                        // slice didn't explicitly reason about.
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler))
                .httpBasic(AbstractHttpConfigurer::disable)
                // Slice 4: form login against DbUserDetailsService, auto-wired the same way the
                // starter's AuthenticationManagerBuilder auto-detects any single UserDetailsService
                // + PasswordEncoder bean pair -- no explicit DaoAuthenticationProvider bean needed
                // since there's exactly one of each in this context.
                .formLogin(form -> form.loginPage("/login"))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new HtmxAuthenticationEntryPoint()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID")
                        .invalidateHttpSession(true));

        return http.build();
    }

    /**
     * Delegating encoder stores hashes with an id prefix ({@code {bcrypt}...}), so the
     * algorithm travels with each hash and can be migrated per-user later without a schema
     * change -- the reason password_hash is VARCHAR(255) rather than sized to bare bcrypt's
     * 60 chars. Encodes new passwords with bcrypt (the current factory default).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
