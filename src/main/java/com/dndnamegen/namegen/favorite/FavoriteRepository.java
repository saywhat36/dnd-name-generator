package com.dndnamegen.namegen.favorite;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    /**
     * Ordered by id as a tiebreaker after created_at -- two favorites added in quick
     * succession can land on the same Instant (client-side createdAt, see Favorite's
     * constructor), and created_at alone gives no stable order between them.
     */
    List<Favorite> findByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);

    Optional<Favorite> findByOwnerIdAndNameId(Long ownerId, Long nameId);

    void deleteByOwnerIdAndNameId(Long ownerId, Long nameId);

    /**
     * Ids only, not full rows -- the browse page only needs membership per name id to mark
     * already-favorited names, not the full Favorite/Name data listFavorites returns.
     * Explicit @Query rather than a derived findNameIdByOwnerId: the session-keyed equivalent
     * of this derivation, despite matching Favorite.nameId, produced a query Hibernate 6.5.3
     * selected as full Favorite rows against a declared List<Long> return type
     * ("QueryTypeMismatchException: ... multiple selections"), reproduced by actually running
     * the app -- not a hypothetical edge case worked around speculatively.
     */
    @Query("SELECT f.nameId FROM Favorite f WHERE f.ownerId = :ownerId")
    List<Long> findNameIdByOwnerId(@Param("ownerId") Long ownerId);
}
