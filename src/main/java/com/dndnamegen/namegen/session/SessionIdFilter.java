package com.dndnamegen.namegen.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mints a server-issued session id cookie for anonymous users. This cookie is the
 * identity mechanism for favorites/reports until Phase 2 auth exists.
 */
@Component
public class SessionIdFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "namegen_session_id";
    public static final String REQUEST_ATTRIBUTE = "sessionId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String sessionId = readSessionId(request);
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(COOKIE_NAME, sessionId);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setMaxAge((int) java.time.Duration.ofDays(365).toSeconds());
            response.addCookie(cookie);
        }
        request.setAttribute(REQUEST_ATTRIBUTE, sessionId);
        filterChain.doFilter(request, response);
    }

    /**
     * Only trusts a cookie value that matches the UUID format this filter mints --
     * a blank or arbitrary client-supplied value would otherwise be accepted as-is
     * and treated as a valid session identity.
     */
    private String readSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && isValidSessionId(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isValidSessionId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
