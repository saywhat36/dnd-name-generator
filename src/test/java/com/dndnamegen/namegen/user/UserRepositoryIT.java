package com.dndnamegen.namegen.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the users persistence primitives against a real Postgres -- the V4 migration,
 * the username_norm derivation, and the uq_users_username_norm unique constraint. Mirrors
 * NameRepositoryIT's Testcontainers setup. A mocked UserRepository could not catch the
 * constraint violation, which only surfaces once a real JPA provider flushes the INSERT.
 */
@Testcontainers
@SpringBootTest
class UserRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private UserRepository userRepository;

    // The container is shared across tests in this class and @SpringBootTest does not roll back,
    // so clear the (unseeded) users table between tests to keep username_norm collisions
    // intra-test. Safe to delete all: no favorites reference a user in this suite.
    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void save_should_PersistUserAndDeriveNormalizedUsername_When_UsernameIsNew() {
        User saved = userRepository.saveAndFlush(new User("Gandalf", "{bcrypt}$2a$10$7EqJtq98hPqEX7fNZaFWoO"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUsernameNorm()).isEqualTo("gandalf");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<User> found = userRepository.findByUsernameNorm("gandalf");
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("Gandalf");
        assertThat(userRepository.existsByUsernameNorm("gandalf")).isTrue();
        assertThat(userRepository.existsByUsernameNorm("saruman")).isFalse();
    }

    @Test
    void save_should_RejectDuplicate_When_UsernameNormCollidesAcrossCasing() {
        userRepository.saveAndFlush(new User("Gandalf", "{bcrypt}$2a$10$firstfirstfirstfirstfiA"));

        // "gandalf" normalizes to the same username_norm as "Gandalf" above -> unique violation
        // on uq_users_username_norm, which is the whole point of storing the normalized form.
        assertThatThrownBy(() ->
                        userRepository.saveAndFlush(new User("gandalf", "{bcrypt}$2a$10$secondsecondsecondsecoB")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
