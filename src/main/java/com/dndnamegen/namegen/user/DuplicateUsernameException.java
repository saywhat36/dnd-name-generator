package com.dndnamegen.namegen.user;

/**
 * Thrown when a registration's username collides with an existing account -- either caught by
 * UserService.register's existsByUsernameNorm precheck, or, in the concurrent-registration race
 * that precheck can't fully guard against, surfaced from the uq_users_username_norm constraint
 * violation instead of a raw DataIntegrityViolationException.
 */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String username) {
        super("Username already taken: " + username);
    }

    public DuplicateUsernameException(String username, Throwable cause) {
        super("Username already taken: " + username, cause);
    }
}
