package com.dndnamegen.namegen.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * Pure constructor/normalization behaviour -- no Spring context or Docker, so this runs
 * locally unlike UserRepositoryIT. Guards the input validation the endpoint slice will
 * lean on when it feeds request payloads straight into new User(...).
 */
class UserTest {

    @Test
    void constructor_should_DeriveNormalizedUsername_When_UsernameHasMixedCaseAndWhitespace() {
        User user = new User("  Gandalf  ", "{bcrypt}hash");

        assertThat(user.getUsername()).isEqualTo("  Gandalf  ");
        assertThat(user.getUsernameNorm()).isEqualTo("gandalf");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test
    void constructor_should_RejectNullUsername() {
        assertThatIllegalArgumentException().isThrownBy(() -> new User(null, "{bcrypt}hash"));
    }

    @Test
    void constructor_should_RejectBlankUsername() {
        assertThatIllegalArgumentException().isThrownBy(() -> new User("   ", "{bcrypt}hash"));
    }

    @Test
    void constructor_should_RejectNullPasswordHash() {
        assertThatNullPointerException().isThrownBy(() -> new User("Gandalf", null));
    }

    @Test
    void normalizeUsername_should_RejectNull() {
        assertThatNullPointerException().isThrownBy(() -> User.normalizeUsername(null));
    }
}
