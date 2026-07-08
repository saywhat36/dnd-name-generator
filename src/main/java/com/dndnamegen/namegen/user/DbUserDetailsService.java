package com.dndnamegen.namegen.user;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads accounts by {@code username_norm} for Spring Security's {@code DaoAuthenticationProvider}.
 * Every account maps to a single hardcoded {@code ROLE_USER} authority -- there's no roles table
 * yet, so anything more granular than "authenticated" doesn't exist to model. Route-level
 * authorization stays out of scope for this slice (see docs/ROADMAP.md's still-unchecked
 * "Route-level security" item); a roles table is future work once a route/feature actually needs
 * more than one authority to distinguish.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DbUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Normalizes the incoming username the same way {@code UserService.register} derives
     * {@code username_norm}, so a login attempt for "Gandalf" finds the row stored for
     * "gandalf". The returned {@link UserDetails} carries the as-entered {@code username} (not
     * the normalized form) so {@code Authentication.getName()} reflects what the user typed at
     * registration time.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository
                .findByUsernameNorm(User.normalizeUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("No account for username: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
    }
}
