package com.dndnamegen.namegen.user;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads accounts by {@code username_norm} for Spring Security's {@code DaoAuthenticationProvider}.
 * Maps {@code users.role} to a single {@code ROLE_} + role authority -- one role per account, so
 * a one-element authority list is correct, not a placeholder. Route-level authorization itself
 * (actually requiring {@code ROLE_ADMIN} on a route) stays out of scope for this slice (see
 * docs/ROADMAP.md's still-unchecked "Route-level security" item); this only makes the real role
 * available to authorize against once that lands.
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

        return new AppUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.isEnabled(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
    }
}
