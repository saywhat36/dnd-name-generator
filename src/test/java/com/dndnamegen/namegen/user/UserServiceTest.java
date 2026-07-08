package com.dndnamegen.namegen.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Mocked repo/encoder, no Spring context -- hits the pre-existing local JDK/Mockito
 * inline-mock-maker gap documented in docs/DECISIONS.md (slice 1), so this won't run
 * against `./mvnw test` locally either; correctness here is `./mvnw test-compile` plus
 * reasoning, same as the rest of this slice per the task note.
 */
class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserService userService = new UserService(userRepository, passwordEncoder);

    @Test
    void register_should_SaveEncodedPasswordUnderNormalizedUsername_When_UsernameIsNew() {
        when(userRepository.existsByUsernameNorm("gandalf")).thenReturn(false);
        when(passwordEncoder.encode("hunter2!!")).thenReturn("{bcrypt}encoded");
        User saved = new User("Gandalf", "{bcrypt}encoded");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        User result = userService.register("Gandalf", "hunter2!!");

        assertThat(result).isSameAs(saved);
        verify(passwordEncoder).encode("hunter2!!");
    }

    @Test
    void register_should_ThrowDuplicateUsernameException_When_NormalizedUsernameAlreadyExists() {
        when(userRepository.existsByUsernameNorm("gandalf")).thenReturn(true);

        assertThatThrownBy(() -> userService.register("Gandalf", "hunter2!!"))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * Mirrors FavoriteServiceTest's concurrent-race test: the precheck passes for both
     * callers, one wins the insert, the loser's save() throws off uq_users_username_norm.
     * Unlike favorites, the loser can't be handed the winner's row (that would be someone
     * else's account), so this must surface as DuplicateUsernameException rather than the
     * raw DataIntegrityViolationException.
     */
    @Test
    void register_should_ThrowDuplicateUsernameException_When_ConcurrentRegistrationRaceViolatesUniqueConstraint() {
        when(userRepository.existsByUsernameNorm("gandalf")).thenReturn(false);
        when(passwordEncoder.encode("hunter2!!")).thenReturn("{bcrypt}encoded");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> userService.register("Gandalf", "hunter2!!"))
                .isInstanceOf(DuplicateUsernameException.class);
    }
}
