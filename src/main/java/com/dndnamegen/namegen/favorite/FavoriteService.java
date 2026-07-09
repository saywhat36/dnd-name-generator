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
 * Add/remove/list favorites, keyed on {@link Identity} -- session_id for anonymous requests,
 * owner_id for authenticated ones (see docs/DECISIONS.md, identity resolution slice). Branches
 * internally rather than exposing separate session/owner methods, since every operation's shape
 * is otherwise identical between the two.
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
     * Idempotent: returns the existing row if this name is already favorited by this identity. A
     * single-row save() here is not the NameInsertDao batch-poisoning scenario -- a
     * duplicate-insert race just throws a catchable DataIntegrityViolationException off the
     * (session_id, name_id) or (owner_id, name_id) unique constraint, handled by re-reading the
     * row the other writer just inserted.
     */
    public Favorite addFavorite(Identity identity, Long nameId) {
        if (identity.isAuthenticated()) {
            return favoriteRepository
                    .findByOwnerIdAndNameId(identity.ownerId(), nameId)
                    .orElseGet(() -> saveNewForOwner(identity.ownerId(), nameId));
        }
        return favoriteRepository
                .findBySessionIdAndNameId(identity.sessionId(), nameId)
                .orElseGet(() -> saveNewForSession(identity.sessionId(), nameId));
    }

    private Favorite saveNewForOwner(Long ownerId, Long nameId) {
        try {
            return favoriteRepository.save(new Favorite(ownerId, nameId));
        } catch (DataIntegrityViolationException e) {
            return favoriteRepository.findByOwnerIdAndNameId(ownerId, nameId).orElseThrow(() -> e);
        }
    }

    private Favorite saveNewForSession(String sessionId, Long nameId) {
        try {
            return favoriteRepository.save(new Favorite(sessionId, nameId));
        } catch (DataIntegrityViolationException e) {
            return favoriteRepository
                    .findBySessionIdAndNameId(sessionId, nameId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * @Transactional here because deleteByOwnerIdAndNameId/deleteBySessionIdAndNameId are
     * derived delete queries, which Spring Data requires to run inside a transaction (see
     * NameService.flagName for the same pattern with an explicit @Modifying query).
     */
    @Transactional
    public void removeFavorite(Identity identity, Long nameId) {
        if (identity.isAuthenticated()) {
            favoriteRepository.deleteByOwnerIdAndNameId(identity.ownerId(), nameId);
        } else {
            favoriteRepository.deleteBySessionIdAndNameId(identity.sessionId(), nameId);
        }
    }

    /**
     * findAllById does not preserve input order, so the result is re-sorted to match
     * favorite order (most recently favorited first). Favorited name ids with no matching
     * Name row (there is no delete path for Name today, but nothing rules one out later)
     * are dropped rather than left as a null entry -- FavoriteController maps this list
     * straight into NameResponse, which would NPE on a null Name otherwise.
     */
    public List<Name> listFavorites(Identity identity) {
        List<Favorite> favorites = identity.isAuthenticated()
                ? favoriteRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(identity.ownerId())
                : favoriteRepository.findBySessionIdOrderByCreatedAtDescIdDesc(identity.sessionId());
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
        List<Long> nameIds = identity.isAuthenticated()
                ? favoriteRepository.findNameIdByOwnerId(identity.ownerId())
                : favoriteRepository.findNameIdBySessionId(identity.sessionId());
        return Set.copyOf(nameIds);
    }
}
