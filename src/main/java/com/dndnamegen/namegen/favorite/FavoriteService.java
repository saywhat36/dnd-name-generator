package com.dndnamegen.namegen.favorite;

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

/**
 * Add/remove/list favorites, keyed on session_id per docs/ARCHITECTURE.md
 * ("favorites" table) -- owner_id stays unpopulated until Phase 2 auth exists.
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
     * Idempotent: returns the existing row if this name is already favorited by this
     * session. A single-row save() here is not the NameInsertDao batch-poisoning scenario --
     * a duplicate-insert race just throws a catchable DataIntegrityViolationException off the
     * (session_id, name_id) unique constraint, handled by re-reading the row the other writer
     * just inserted.
     */
    public Favorite addFavorite(String sessionId, Long nameId) {
        return favoriteRepository
                .findBySessionIdAndNameId(sessionId, nameId)
                .orElseGet(() -> saveNew(sessionId, nameId));
    }

    private Favorite saveNew(String sessionId, Long nameId) {
        try {
            return favoriteRepository.save(new Favorite(sessionId, nameId));
        } catch (DataIntegrityViolationException e) {
            return favoriteRepository
                    .findBySessionIdAndNameId(sessionId, nameId)
                    .orElseThrow(() -> e);
        }
    }

    public void removeFavorite(String sessionId, Long nameId) {
        favoriteRepository.deleteBySessionIdAndNameId(sessionId, nameId);
    }

    /**
     * findAllById does not preserve input order, so the result is re-sorted to match
     * favorite order (most recently favorited first). Favorited name ids with no matching
     * Name row (there is no delete path for Name today, but nothing rules one out later)
     * are dropped rather than left as a null entry -- FavoriteController maps this list
     * straight into NameResponse, which would NPE on a null Name otherwise.
     */
    public List<Name> listFavorites(String sessionId) {
        List<Favorite> favorites = favoriteRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId);
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
    public Set<Long> getFavoritedNameIds(String sessionId) {
        return Set.copyOf(favoriteRepository.findNameIdBySessionId(sessionId));
    }
}
