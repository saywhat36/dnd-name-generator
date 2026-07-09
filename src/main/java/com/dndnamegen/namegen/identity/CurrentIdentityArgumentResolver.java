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
 * Resolves an {@link Identity} controller parameter without controllers reaching into
 * {@code SecurityContextHolder} or the raw {@code sessionId} request attribute themselves.
 * Authenticated requests (principal is {@link AppUserDetails}, populated by
 * {@code DbUserDetailsService}) resolve to {@link Identity#ofUser}; everyone else -- including
 * Spring Security's anonymous-authentication placeholder -- falls back to
 * {@link Identity#ofSession} using {@code SessionIdFilter}'s request attribute, which is always
 * present regardless of authentication state.
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
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AppUserDetails principal) {
            return Identity.ofUser(principal.getOwnerId(), sessionId);
        }
        return Identity.ofSession(sessionId);
    }
}
