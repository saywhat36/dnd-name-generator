package com.dndnamegen.namegen.favorite;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    /**
     * Ordered by id as a tiebreaker after created_at -- two favorites added in quick
     * succession can land on the same Instant (client-side createdAt, see Favorite's
     * constructor), and created_at alone gives no stable order between them.
     */
    List<Favorite> findBySessionIdOrderByCreatedAtDescIdDesc(String sessionId);

    Optional<Favorite> findBySessionIdAndNameId(String sessionId, Long nameId);

    void deleteBySessionIdAndNameId(String sessionId, Long nameId);
}
