package com.dndnamegen.namegen.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class FavoriteServiceTest {

    private static final Identity SESSION_IDENTITY = Identity.ofSession("session-1");
    private static final Identity OWNER_IDENTITY = Identity.ofUser(42L, "session-1");

    private final FavoriteRepository favoriteRepository = mock(FavoriteRepository.class);
    private final NameRepository nameRepository = mock(NameRepository.class);
    private final FavoriteService favoriteService = new FavoriteService(favoriteRepository, nameRepository);

    @Test
    void addFavorite_should_ReturnExistingRow_When_AlreadyFavoritedBySession() {
        Favorite existing = new Favorite("session-1", 1L);
        when(favoriteRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.of(existing));

        Favorite result = favoriteService.addFavorite(SESSION_IDENTITY, 1L);

        assertThat(result).isSameAs(existing);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void addFavorite_should_SaveNewRow_When_NotYetFavoritedBySession() {
        when(favoriteRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.empty());
        Favorite saved = new Favorite("session-1", 1L);
        when(favoriteRepository.save(any(Favorite.class))).thenReturn(saved);

        Favorite result = favoriteService.addFavorite(SESSION_IDENTITY, 1L);

        assertThat(result).isSameAs(saved);
    }

    /**
     * Simulates two concurrent addFavorite calls for the same (sessionId, nameId): both pass
     * the initial findBySessionIdAndNameId check, one wins the insert, the other's save()
     * throws off the unique constraint and must return the winner's row instead of propagating.
     */
    @Test
    void addFavorite_should_ReturnWinnersRow_When_ConcurrentInsertRaceViolatesSessionUniqueConstraint() {
        Favorite winnersRow = new Favorite("session-1", 1L);
        when(favoriteRepository.findBySessionIdAndNameId("session-1", 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(favoriteRepository.save(any(Favorite.class))).thenThrow(new DataIntegrityViolationException("dup"));

        Favorite result = favoriteService.addFavorite(SESSION_IDENTITY, 1L);

        assertThat(result).isSameAs(winnersRow);
    }

    @Test
    void addFavorite_should_ReturnExistingRow_When_AlreadyFavoritedByOwner() {
        Favorite existing = new Favorite(42L, 1L);
        when(favoriteRepository.findByOwnerIdAndNameId(42L, 1L)).thenReturn(Optional.of(existing));

        Favorite result = favoriteService.addFavorite(OWNER_IDENTITY, 1L);

        assertThat(result).isSameAs(existing);
        verify(favoriteRepository, never()).save(any());
        verify(favoriteRepository, never()).findBySessionIdAndNameId(any(), any());
    }

    @Test
    void addFavorite_should_SaveNewOwnerKeyedRow_When_NotYetFavoritedByOwner() {
        when(favoriteRepository.findByOwnerIdAndNameId(42L, 1L)).thenReturn(Optional.empty());
        Favorite saved = new Favorite(42L, 1L);
        when(favoriteRepository.save(any(Favorite.class))).thenReturn(saved);

        Favorite result = favoriteService.addFavorite(OWNER_IDENTITY, 1L);

        assertThat(result).isSameAs(saved);
    }

    @Test
    void addFavorite_should_ReturnWinnersRow_When_ConcurrentInsertRaceViolatesOwnerUniqueConstraint() {
        Favorite winnersRow = new Favorite(42L, 1L);
        when(favoriteRepository.findByOwnerIdAndNameId(42L, 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(favoriteRepository.save(any(Favorite.class))).thenThrow(new DataIntegrityViolationException("dup"));

        Favorite result = favoriteService.addFavorite(OWNER_IDENTITY, 1L);

        assertThat(result).isSameAs(winnersRow);
    }

    @Test
    void removeFavorite_should_DeleteBySessionIdAndNameId_When_IdentityIsSessionKeyed() {
        favoriteService.removeFavorite(SESSION_IDENTITY, 1L);

        verify(favoriteRepository).deleteBySessionIdAndNameId("session-1", 1L);
    }

    @Test
    void removeFavorite_should_DeleteByOwnerIdAndNameId_When_IdentityIsOwnerKeyed() {
        favoriteService.removeFavorite(OWNER_IDENTITY, 1L);

        verify(favoriteRepository).deleteByOwnerIdAndNameId(42L, 1L);
        verify(favoriteRepository, never()).deleteBySessionIdAndNameId(any(), any());
    }

    @Test
    void listFavorites_should_ReturnNamesInFavoriteOrder_When_RepositoryReturnsThemOutOfOrder() {
        Favorite favoriteOfName2 = new Favorite("session-1", 2L);
        Favorite favoriteOfName1 = new Favorite("session-1", 1L);
        when(favoriteRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-1"))
                .thenReturn(List.of(favoriteOfName2, favoriteOfName1));

        Name name1 = mock(Name.class);
        when(name1.getId()).thenReturn(1L);
        Name name2 = mock(Name.class);
        when(name2.getId()).thenReturn(2L);
        // Deliberately returned in the opposite order to the favorites list, since
        // findAllById does not preserve input order.
        when(nameRepository.findAllById(List.of(2L, 1L))).thenReturn(List.of(name1, name2));

        List<Name> result = favoriteService.listFavorites(SESSION_IDENTITY);

        assertThat(result).containsExactly(name2, name1);
    }

    @Test
    void listFavorites_should_ReturnEmptyList_When_SessionHasNoFavorites() {
        when(favoriteRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-1")).thenReturn(List.of());
        when(nameRepository.findAllById(List.of())).thenReturn(List.of());

        List<Name> result = favoriteService.listFavorites(SESSION_IDENTITY);

        assertThat(result).isEmpty();
    }

    /**
     * A favorited nameId with no matching Name row (e.g. the Name was later removed by some
     * future path) must be dropped, not surfaced as a null entry -- FavoriteController maps
     * this list straight into NameResponse::from, which would NPE on a null Name.
     */
    @Test
    void listFavorites_should_OmitEntry_When_FavoritedNameIdHasNoMatchingNameRow() {
        Favorite favoriteOfMissingName = new Favorite("session-1", 99L);
        Favorite favoriteOfName1 = new Favorite("session-1", 1L);
        when(favoriteRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-1"))
                .thenReturn(List.of(favoriteOfMissingName, favoriteOfName1));

        Name name1 = mock(Name.class);
        when(name1.getId()).thenReturn(1L);
        when(nameRepository.findAllById(List.of(99L, 1L))).thenReturn(List.of(name1));

        List<Name> result = favoriteService.listFavorites(SESSION_IDENTITY);

        assertThat(result).containsExactly(name1);
    }

    @Test
    void listFavorites_should_UseOwnerIdOrdering_When_IdentityIsOwnerKeyed() {
        Favorite favoriteOfName1 = new Favorite(42L, 1L);
        when(favoriteRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(42L)).thenReturn(List.of(favoriteOfName1));

        Name name1 = mock(Name.class);
        when(name1.getId()).thenReturn(1L);
        when(nameRepository.findAllById(List.of(1L))).thenReturn(List.of(name1));

        List<Name> result = favoriteService.listFavorites(OWNER_IDENTITY);

        assertThat(result).containsExactly(name1);
        verify(favoriteRepository, never()).findBySessionIdOrderByCreatedAtDescIdDesc(any());
    }

    @Test
    void getFavoritedNameIds_should_ReturnIdsAsASet_When_SessionHasFavorites() {
        when(favoriteRepository.findNameIdBySessionId("session-1")).thenReturn(List.of(2L, 1L));

        Set<Long> result = favoriteService.getFavoritedNameIds(SESSION_IDENTITY);

        assertThat(result).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void getFavoritedNameIds_should_ReturnEmptySet_When_SessionHasNoFavorites() {
        when(favoriteRepository.findNameIdBySessionId("session-1")).thenReturn(List.of());

        Set<Long> result = favoriteService.getFavoritedNameIds(SESSION_IDENTITY);

        assertThat(result).isEmpty();
    }

    @Test
    void getFavoritedNameIds_should_UseOwnerId_When_IdentityIsOwnerKeyed() {
        when(favoriteRepository.findNameIdByOwnerId(42L)).thenReturn(List.of(1L));

        Set<Long> result = favoriteService.getFavoritedNameIds(OWNER_IDENTITY);

        assertThat(result).containsExactly(1L);
        verify(favoriteRepository, never()).findNameIdBySessionId(any());
    }
}
