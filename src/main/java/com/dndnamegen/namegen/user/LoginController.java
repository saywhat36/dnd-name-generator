package com.dndnamegen.namegen.user;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * GET /login only -- Spring Security's {@code UsernamePasswordAuthenticationFilter} owns the
 * POST directly (see {@code WebSecurityConfig}'s {@code formLogin} config), the same GET/POST
 * split {@code RegistrationController} uses, except here the POST is framework-owned rather than
 * handled by this controller. {@code login.html} reads {@code ?registered}, {@code ?error}, and
 * {@code ?logout} query params (set by {@code RegistrationController}'s success redirect, Spring
 * Security's default failure URL, and the configured {@code logoutSuccessUrl} respectively) to
 * show the right message -- no flash-attribute infrastructure exists in this codebase yet, same
 * as the rationale already recorded for {@code RegistrationController}.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String showForm() {
        return "login";
    }
}
