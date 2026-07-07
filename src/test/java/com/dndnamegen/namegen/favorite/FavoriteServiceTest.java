package com.dndnamegen.namegen.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class FavoriteServiceTest {

    private final FavoriteRepository favoriteRepository = mock(FavoriteRepository.class);
    private final NameRepository nameRepository = mock(NameRepository.class);
    private final FavoriteService favoriteService = new FavoriteService(favoriteRepository, nameRepository);

    @Test
    void addFavorite_should_ReturnExistingRow_When_AlreadyFavorited() {
        Favorite existing = new Favorite("session-1", 1L);
        when(favoriteRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.of(existing));

        Favorite result = favoriteService.addFavorite("session-1", 1L);

        assertThat(result).isSameAs(existing);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void addFavorite_should_SaveNewRow_When_NotYetFavorited() {
        when(favoriteRepository.findBySessionIdAndNameId("session-1", 1L)).thenReturn(Optional.empty());
        Favorite saved = new Favorite("session-1", 1L);
        when(favoriteRepository.save(any(Favorite.class))).thenReturn(saved);

        Favorite result = favoriteService.addFavorite("session-1", 1L);

        assertThat(result).isSameAs(saved);
    }

    /**
     * Simulates two concurrent addFavorite calls for the same (sessionId, nameId): both pass
     * the initial findBySessionIdAndNameId check, one wins the insert, the other's save()
     * throws off the unique constraint and must return the winner's row instead of propagating.
     */
    @Test
    void addFavorite_should_ReturnWinnersRow_When_ConcurrentInsertRaceViolatesUniqueConstraint() {
        Favorite winnersRow = new Favorite("session-1", 1L);
        when(favoriteRepository.findBySessionIdAndNameId("session-1", 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(favoriteRepository.save(any(Favorite.class))).thenThrow(new DataIntegrityViolationException("dup"));

        Favorite result = favoriteService.addFavorite("session-1", 1L);

        assertThat(result).isSameAs(winnersRow);
    }

    @Test
    void removeFavorite_should_DeleteBySessionIdAndNameId() {
        favoriteService.removeFavorite("session-1", 1L);

        verify(favoriteRepository).deleteBySessionIdAndNameId("session-1", 1L);
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

        List<Name> result = favoriteService.listFavorites("session-1");

        assertThat(result).containsExactly(name2, name1);
    }

    @Test
    void listFavorites_should_ReturnEmptyList_When_SessionHasNoFavorites() {
        when(favoriteRepository.findBySessionIdOrderByCreatedAtDescIdDesc("session-1")).thenReturn(List.of());
        when(nameRepository.findAllById(List.of())).thenReturn(List.of());

        List<Name> result = favoriteService.listFavorites("session-1");

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

        List<Name> result = favoriteService.listFavorites("session-1");

        assertThat(result).containsExactly(name1);
    }
}
