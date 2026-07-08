package com.dndnamegen.namegen.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameNorm(String usernameNorm);

    boolean existsByUsernameNorm(String usernameNorm);
}
