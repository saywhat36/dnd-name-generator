package com.dndnamegen.namegen.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * existsByUsernameNorm is a check-then-act precheck, not the actual guarantee -- it's
     * racy under two concurrent registrations for the same username. uq_users_username_norm
     * is what actually prevents the duplicate; save() below is wrapped in the same
     * DataIntegrityViolationException catch FavoriteService.saveNew uses for its own
     * check-then-act race, except here the loser can't just return the winner's row (that
     * would hand back someone else's account) -- it surfaces as DuplicateUsernameException
     * instead, same as the precheck's fast-path rejection.
     */
    public User register(String username, String rawPassword) {
        String usernameNorm = User.normalizeUsername(username);
        if (userRepository.existsByUsernameNorm(usernameNorm)) {
            throw new DuplicateUsernameException(username);
        }
        try {
            return userRepository.save(new User(username, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUsernameException(username, e);
        }
    }
}
