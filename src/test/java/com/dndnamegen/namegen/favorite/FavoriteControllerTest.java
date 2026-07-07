package com.dndnamegen.namegen.favorite;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.session.SessionIdFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(FavoriteController.class)
class FavoriteControllerTest {

    private static final String SESSION_ID = "session-1";

    @Autowired private MockMvc mockMvc;

    @MockBean private FavoriteService favoriteService;

    @MockBean private NameRepository nameRepository;

    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        return builder.requestAttr(SessionIdFilter.REQUEST_ATTRIBUTE, SESSION_ID);
    }

    @Test
    void addFavorite_should_ReturnCreated_When_NameExists() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withSession(post("/favorites").param("nameId", "1")))
                .andExpect(status().isCreated());

        verify(favoriteService).addFavorite(SESSION_ID, 1L);
    }

    @Test
    void addFavorite_should_ReturnNotFound_When_NameIdDoesNotReferenceARealName() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(withSession(post("/favorites").param("nameId", "1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeFavorite_should_ReturnNoContent() throws Exception {
        mockMvc.perform(withSession(delete("/favorites/1"))).andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(SESSION_ID, 1L);
    }

    @Test
    void listFavorites_should_ReturnFavoritedNames() throws Exception {
        Name favoritedName = mock(Name.class);
        when(favoritedName.getId()).thenReturn(1L);
        when(favoritedName.getDisplayName()).thenReturn("Aelric");
        when(favoriteService.listFavorites(SESSION_ID)).thenReturn(List.of(favoritedName));

        mockMvc.perform(withSession(get("/favorites")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Aelric"));

        verify(favoriteService).listFavorites(eq(SESSION_ID));
    }
}
