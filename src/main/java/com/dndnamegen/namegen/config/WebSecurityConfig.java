package com.dndnamegen.namegen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Slice 1 of the security rollout: wire up Spring Security and CSRF without changing
 * any observable behaviour. Every route stays anonymous-accessible -- route locking
 * (login required, per-route authorization) is deferred to a later slice. This class
 * exists purely so htmx's POST/DELETE requests keep working once CSRF protection is on.
 */
@Configuration
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
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler))
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
