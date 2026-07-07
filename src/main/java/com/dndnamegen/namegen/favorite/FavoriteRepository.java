package com.dndnamegen.namegen.favorite;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    Optional<Favorite> findBySessionIdAndNameId(String sessionId, Long nameId);

    void deleteBySessionIdAndNameId(String sessionId, Long nameId);
}
