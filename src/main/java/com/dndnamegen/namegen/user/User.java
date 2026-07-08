package com.dndnamegen.namegen.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "username_norm", nullable = false)
    private String usernameNorm;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {}

    /**
     * Sets createdAt and enabled here rather than leaning on the column DEFAULTs -- like
     * Favorite, Hibernate sends whatever the field holds (NULL / false) on INSERT and never
     * consults the DB default, so the defaults would only ever apply to hand-written SQL.
     */
    public User(String username, String passwordHash) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
        this.username = username;
        this.usernameNorm = normalizeUsername(username);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    /**
     * Mirrors the username_norm column so "Gandalf" and "gandalf" collide on the
     * uq_users_username_norm unique constraint. Deliberately simpler than
     * DeduplicationService.normalize -- trim + lowercase, no Unicode NFC pass, which is
     * overkill for usernames. Kept static so lookup callers can normalize a query input
     * the same way rows were stored -- the requireNonNull guards that path too, so a null
     * turns into a clear message rather than an NPE deep inside trim().
     */
    public static String normalizeUsername(String username) {
        return Objects.requireNonNull(username, "username must not be null").trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getUsernameNorm() {
        return usernameNorm;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
