package com.dndnamegen.namegen.identity;

import com.dndnamegen.namegen.session.SessionIdFilter;
import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves an {@link Identity} controller parameter for endpoints that require an authenticated
 * request -- favorites, reports, and the browse pages all require login now, with no anonymous
 * fallback (see docs/DECISIONS.md, identity resolution slice revision). Throws
 * {@link InsufficientAuthenticationException} when the principal is missing or is Spring
 * Security's anonymous-authentication placeholder, rather than the {@link
 * AppUserDetails} this resolver requires -- route-level enforcement (redirecting unauthenticated
 * requests to login before they ever reach a controller) is Roadmap Phase 2's still-open
 * "Route-level security" item, tracked separately.
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
            throw new InsufficientAuthenticationException(
                    "Identity resolution requires an authenticated request; anonymous access is no"
                            + " longer supported for this endpoint");
        }
        return Identity.of(principal.getOwnerId(), sessionId);
    }
}
