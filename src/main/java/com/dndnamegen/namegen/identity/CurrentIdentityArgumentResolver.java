package com.dndnamegen.namegen.identity;

import com.dndnamegen.namegen.session.SessionIdFilter;
import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves an {@link Identity} controller parameter for both the public browse routes and the
 * authenticated-only favorites/reports routes. Returns {@link Identity#anonymous(String)} when
 * there is no authenticated {@link AppUserDetails} principal, rather than throwing, as of slice
 * 7 (see docs/DECISIONS.md) -- route-level security (the filter chain in {@code
 * WebSecurityConfig}, backed by {@code @PreAuthorize} on the mutating controller methods) is now
 * the actual gate for favorites/reports, so those controllers never reach this resolver as an
 * anonymous request; it can afford to be tolerant here so the now-public {@code
 * NameBrowserController} routes get a usable (if ownerless) {@code Identity} instead of a
 * forced redirect to {@code /login} just to view names.
 */
public class CurrentIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Identity.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        String sessionId = (String) request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserDetails principal)) {
            return Identity.anonymous(sessionId);
        }
        return Identity.of(principal.getOwnerId(), sessionId);
    }
}
