package com.dndnamegen.namegen.favorite;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.session.SessionIdFilter;
import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(FavoriteController.class)
class FavoriteControllerTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final Identity IDENTITY = Identity.of(42L, SESSION_ID);

    @Autowired private MockMvc mockMvc;

    @MockBean private FavoriteService favoriteService;

    @MockBean private NameRepository nameRepository;

    /**
     * @WebMvcTest auto-registers Filter beans, so the real SessionIdFilter runs in this slice.
     * Setting the request attribute directly is not enough -- with no cookie present,
     * SessionIdFilter mints its own random session id and overwrites the attribute before
     * CurrentIdentityArgumentResolver reads it. Supplying a cookie in the real SessionIdFilter's
     * own format (a valid UUID) makes the filter recognize and pass through this session id
     * instead of minting a new one.
     */
    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        return builder.cookie(new Cookie(SessionIdFilter.COOKIE_NAME, SESSION_ID)).with(csrf());
    }

    /**
     * Authenticates as an {@link AppUserDetails} principal (ownerId 42) on top of the session
     * cookie -- favorites require an authenticated request, enforced at the filter-chain level
     * as of slice 7 (see docs/DECISIONS.md, WebSecurityConfig), so every test here except the
     * anonymous-rejection case authenticates.
     */
    private static MockHttpServletRequestBuilder withOwner(MockHttpServletRequestBuilder builder) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return withSession(builder).with(user(principal));
    }

    @Test
    void addFavorite_should_ReturnCreated_When_NameExists() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withOwner(post("/favorites").param("nameId", "1"))).andExpect(status().isCreated());

        verify(favoriteService).addFavorite(IDENTITY, 1L);
    }

    @Test
    void addFavorite_should_ReturnNotFound_When_NameIdDoesNotReferenceARealName() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(withOwner(post("/favorites").param("nameId", "1"))).andExpect(status().isNotFound());
    }

    /**
     * As of slice 7 (see docs/DECISIONS.md, WebSecurityConfig), POST /favorites requires
     * authentication at the filter-chain level -- an anonymous, non-htmx request never reaches
     * this controller at all; HtmxAuthenticationEntryPoint's browser branch (no HX-Request
     * header) redirects to /login, same 3xx outcome as before route-level security landed, but
     * now enforced ahead of the controller rather than via CurrentIdentityArgumentResolver
     * throwing. See NameBrowserControllerTest's htmx-specific case for the other branch.
     */
    @Test
    void addFavorite_should_Redirect_When_AnonymousBrowserRequest() throws Exception {
        mockMvc.perform(withSession(post("/favorites").param("nameId", "1"))).andExpect(status().is3xxRedirection());
    }

    @Test
    void removeFavorite_should_ReturnNoContent() throws Exception {
        mockMvc.perform(withOwner(delete("/favorites/1"))).andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(IDENTITY, 1L);
    }

    @Test
    void listFavorites_should_ReturnFavoritedNames() throws Exception {
        Name favoritedName = mock(Name.class);
        when(favoritedName.getId()).thenReturn(1L);
        when(favoritedName.getDisplayName()).thenReturn("Aelric");
        when(favoriteService.listFavorites(IDENTITY)).thenReturn(List.of(favoritedName));

        mockMvc.perform(withOwner(get("/favorites")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Aelric"));

        verify(favoriteService).listFavorites(eq(IDENTITY));
    }
}
