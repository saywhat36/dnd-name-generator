package com.dndnamegen.namegen.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Slice 7's htmx-specific piece: htmx swaps a POST/DELETE response's body straight into a
 * target element, so the default {@link LoginUrlAuthenticationEntryPoint}'s 302 (which htmx's
 * XHR-based fetch follows transparently, landing the full {@code login.html} document's markup
 * inside whatever element the original request targeted) would dump a broken nested page into
 * the UI instead of navigating there. htmx's own answer to this is the {@code HX-Redirect}
 * response header, which tells the client to perform a real, full-page {@code window.location}
 * navigation instead of swapping the response body -- but that header only does anything on an
 * htmx-issued request, so this only takes the htmx branch when the {@code HX-Request} header
 * (sent automatically by every htmx-issued request) is present. Every other (non-htmx) request
 * -- ordinary browser navigation to a route requiring login -- keeps the normal 302 behavior via
 * the delegate.
 */
public class HtmxAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String LOGIN_PATH = "/login";

    private final AuthenticationEntryPoint browserEntryPoint = new LoginUrlAuthenticationEntryPoint(LOGIN_PATH);

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        if (request.getHeader("HX-Request") != null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("HX-Redirect", LOGIN_PATH);
            return;
        }
        browserEntryPoint.commence(request, response, authException);
    }
}
