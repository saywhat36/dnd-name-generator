package com.dndnamegen.namegen.user;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * GET/POST /register -- server-rendered account creation. Deliberately re-renders the
 * same template on any failure (validation or duplicate username) rather than redirecting
 * with a flash message, keeping this a single round trip like the rest of the app's
 * Thymeleaf pages (no flash-attribute infrastructure exists yet). See docs/DECISIONS.md.
 */
@Controller
public class RegistrationController {

    /**
     * Letters, digits, underscore, hyphen -- deliberately narrower than the Unicode-aware
     * letter pattern QualityGateService uses for generated names. Usernames double as login
     * identifiers and show up in URLs/logs, so ASCII-only avoids lookalike-character
     * confusables and keeps them simple to type back in at the login form.
     */
    private static final Pattern ALLOWED_USERNAME_CHARACTERS = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final UserService userService;
    private final int minUsernameLength;
    private final int maxUsernameLength;
    private final int minPasswordLength;

    public RegistrationController(
            UserService userService,
            @Value("${app.auth.min-username-length:3}") int minUsernameLength,
            @Value("${app.auth.max-username-length:64}") int maxUsernameLength,
            @Value("${app.auth.min-password-length:8}") int minPasswordLength) {
        this.userService = userService;
        this.minUsernameLength = minUsernameLength;
        this.maxUsernameLength = maxUsernameLength;
        this.minPasswordLength = minPasswordLength;
    }

    @GetMapping("/register")
    public String showForm(Model model) {
        model.addAttribute("form", new RegisterForm("", ""));
        return "register";
    }

    @PostMapping("/register")
    public String register(@ModelAttribute("form") RegisterForm form, Model model) {
        // Trimmed once here so the same value is what gets validated (charset/length) and
        // what gets persisted -- previously validate() checked a trimmed copy while
        // register() below was handed the raw, untrimmed input, so padding whitespace could
        // slip the charset check and still land in the stored username. Caught in review.
        String username = form.username() == null ? "" : form.username().trim();
        String error = validate(username, form.password());
        if (error == null) {
            try {
                userService.register(username, form.password());
                return "redirect:/login?registered";
            } catch (DuplicateUsernameException e) {
                error = "That username is already taken.";
            }
        }
        // Password is dropped on re-render (never echoed back into the form), username
        // is kept so the user isn't forced to retype it after a validation/duplicate error.
        model.addAttribute("form", new RegisterForm(username, ""));
        model.addAttribute("error", error);
        return "register";
    }

    private String validate(String username, String password) {
        if (username.length() < minUsernameLength || username.length() > maxUsernameLength) {
            return "Username must be between " + minUsernameLength + " and " + maxUsernameLength + " characters.";
        }
        if (!ALLOWED_USERNAME_CHARACTERS.matcher(username).matches()) {
            return "Username may only contain letters, numbers, underscores, and hyphens.";
        }
        if (password == null || password.length() < minPasswordLength) {
            return "Password must be at least " + minPasswordLength + " characters.";
        }
        return null;
    }
}
