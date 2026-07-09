package com.dndnamegen.namegen.favorite;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Add/remove/list favorites, keyed on {@link Identity#ownerId()} -- there is no anonymous
 * fallback (see docs/DECISIONS.md, identity resolution slice revision): favorites require an
 * authenticated request, so {@code Identity} always carries an owner id here.
 */
@Service
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final NameRepository nameRepository;

    public FavoriteService(FavoriteRepository favoriteRepository, NameRepository nameRepository) {
        this.favoriteRepository = favoriteRepository;
        this.nameRepository = nameRepository;
    }

    /**
     * Idempotent: returns the existing row if this owner already favorited this name. A
     * single-row save() here is not the NameInsertDao batch-poisoning scenario -- a
     * duplicate-insert race just throws a catchable DataIntegrityViolationException off the
     * (owner_id, name_id) unique constraint, handled by re-reading the row the other writer
     * just inserted.
     */
    public Favorite addFavorite(Identity identity, Long nameId) {
        return favoriteRepository
                .findByOwnerIdAndNameId(identity.ownerId(), nameId)
                .orElseGet(() -> saveNew(identity.ownerId(), nameId));
    }

    private Favorite saveNew(Long ownerId, Long nameId) {
        try {
            return favoriteRepository.save(new Favorite(ownerId, nameId));
        } catch (DataIntegrityViolationException e) {
            return favoriteRepository.findByOwnerIdAndNameId(ownerId, nameId).orElseThrow(() -> e);
        }
    }

    /**
     * @Transactional here because deleteByOwnerIdAndNameId is a derived delete query, which
     * Spring Data requires to run inside a transaction (see NameService.flagName for the same
     * pattern with an explicit @Modifying query).
     */
    @Transactional
    public void removeFavorite(Identity identity, Long nameId) {
        favoriteRepository.deleteByOwnerIdAndNameId(identity.ownerId(), nameId);
    }

    /**
     * findAllById does not preserve input order, so the result is re-sorted to match
     * favorite order (most recently favorited first). Favorited name ids with no matching
     * Name row (there is no delete path for Name today, but nothing rules one out later)
     * are dropped rather than left as a null entry -- FavoriteController maps this list
     * straight into NameResponse, which would NPE on a null Name otherwise.
     */
    public List<Name> listFavorites(Identity identity) {
        List<Favorite> favorites = favoriteRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(identity.ownerId());
        List<Long> orderedNameIds = favorites.stream().map(Favorite::getNameId).toList();

        Map<Long, Name> namesById =
                nameRepository.findAllById(orderedNameIds).stream().collect(Collectors.toMap(Name::getId, Function.identity()));

        return orderedNameIds.stream().map(namesById::get).filter(Objects::nonNull).toList();
    }

    /**
     * Used by the browse page to mark already-favorited names on initial render -- a Set
     * since callers only need membership per name id, not order or the Favorite rows
     * themselves (see listFavorites for the ordered, full-row variant).
     */
    public Set<Long> getFavoritedNameIds(Identity identity) {
        return Set.copyOf(favoriteRepository.findNameIdByOwnerId(identity.ownerId()));
    }
}
