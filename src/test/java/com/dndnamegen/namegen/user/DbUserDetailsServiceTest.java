package com.dndnamegen.namegen.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Mocked repo, no Spring context -- same pre-existing local JDK/Mockito inline-mock-maker gap
 * documented in docs/DECISIONS.md (slice 1), so this won't run against `./mvnw test` locally
 * either.
 */
class DbUserDetailsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DbUserDetailsService userDetailsService = new DbUserDetailsService(userRepository);

    @Test
    void loadUserByUsername_should_ReturnUserDetailsWithRoleUser_When_AccountExists() {
        User user = new User("Gandalf", "{bcrypt}encoded");
        when(userRepository.findByUsernameNorm("gandalf")).thenReturn(Optional.of(user));

        UserDetails result = userDetailsService.loadUserByUsername("Gandalf");

        assertThat(result.getUsername()).isEqualTo("Gandalf");
        assertThat(result.getPassword()).isEqualTo("{bcrypt}encoded");
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    void loadUserByUsername_should_NormalizeLookup_When_CasingDiffersFromStoredUsername() {
        User user = new User("Gandalf", "{bcrypt}encoded");
        when(userRepository.findByUsernameNorm("gandalf")).thenReturn(Optional.of(user));

        UserDetails result = userDetailsService.loadUserByUsername("GANDALF");

        assertThat(result.getUsername()).isEqualTo("Gandalf");
    }

    @Test
    void loadUserByUsername_should_ThrowUsernameNotFoundException_When_NoAccountForUsername() {
        when(userRepository.findByUsernameNorm("gandalf")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("Gandalf"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
