package com.dndnamegen.namegen.user;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
     *
     * <p>The catch re-checks existsByUsernameNorm before concluding the violation was the
     * username collision, the same confirm-before-concluding shape FavoriteService.saveNew
     * uses (it re-reads the row rather than assuming its own explanation). Caught in review:
     * without this, any other constraint failure on the same save() -- e.g. a VARCHAR(64)
     * overflow if a caller ever bypasses RegistrationController's length check -- would be
     * misreported to the user as "that username is already taken" instead of propagating.
     */
    public User register(String username, String rawPassword) {
        String usernameNorm = User.normalizeUsername(username);
        if (userRepository.existsByUsernameNorm(usernameNorm)) {
            throw new DuplicateUsernameException(username);
        }
        try {
            return userRepository.save(new User(username, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByUsernameNorm(usernameNorm)) {
                throw new DuplicateUsernameException(username, e);
            }
            throw e;
        }
    }

    /**
     * Batch-resolves user ids to their display usernames, for callers that hold a set of ids and
     * need the names for display (issue #81: the "user submitted" name view). Returns a
     * {@code id -> username} map containing only the ids that resolve to an existing user -- a
     * missing id (e.g. a since-deleted account) is simply absent, so callers should treat a lookup
     * miss as "no username" rather than assuming every requested id is present. An empty input
     * short-circuits without hitting the database.
     */
    public Map<Long, String> usernamesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
    }
}
