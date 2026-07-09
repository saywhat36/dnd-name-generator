package com.dndnamegen.namegen.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.favorite.FavoriteService;
import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.report.NameReportService;
import com.dndnamegen.namegen.session.SessionIdFilter;
import com.dndnamegen.namegen.user.AppUserDetails;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(NameBrowserController.class)
class NameBrowserControllerTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final Identity IDENTITY = Identity.of(42L, SESSION_ID);
    private static final Identity ANONYMOUS_IDENTITY = Identity.anonymous(SESSION_ID);

    @Autowired private MockMvc mockMvc;

    @MockBean private NameService nameService;

    @MockBean private FavoriteService favoriteService;

    @MockBean private NameReportService nameReportService;

    @MockBean private PoolReplenishmentService poolReplenishmentService;

    /**
     * @WebMvcTest auto-registers Filter beans, so the real SessionIdFilter runs in this
     * slice -- see FavoriteControllerTest for why a cookie (not a directly-set request
     * attribute) is required to control the session id the filter passes through.
     */
    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        return builder.cookie(new Cookie(SessionIdFilter.COOKIE_NAME, SESSION_ID)).with(csrf());
    }

    /**
     * Authenticated on top of the session cookie -- used by the tests covering the
     * authenticated happy path for each route.
     */
    private static MockHttpServletRequestBuilder withOwner(MockHttpServletRequestBuilder builder) {
        AppUserDetails principal =
                new AppUserDetails(42L, "gandalf", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return withSession(builder).with(user(principal));
    }

    /**
     * Every render calls both id-lookup services regardless of whether the result list is
     * empty, so an unstubbed mock returning null would NPE inside the template's
     * favoritedNameIds.contains(...)/reportedNameIds.contains(...) calls. Defaults to "no
     * prior activity" for every test; the one test that cares about pre-disabled buttons
     * overrides this. Also defaults the pool cap high and "not currently generating" so
     * existing tests (none of which stub these) don't need to know about the new
     * generate-more feature's model attributes. Stubs both the owner and anonymous identity
     * shapes -- as of slice 7 (see docs/DECISIONS.md) GET / and GET /browse are public, so an
     * anonymous request resolves an anonymous Identity and still calls these same services.
     */
    @BeforeEach
    void stubNoPriorActivityByDefault() {
        when(favoriteService.getFavoritedNameIds(IDENTITY)).thenReturn(Set.of());
        when(nameReportService.getReportedNameIds(IDENTITY)).thenReturn(Set.of());
        when(favoriteService.getFavoritedNameIds(ANONYMOUS_IDENTITY)).thenReturn(Set.of());
        when(nameReportService.getReportedNameIds(ANONYMOUS_IDENTITY)).thenReturn(Set.of());
        when(poolReplenishmentService.getPoolCapPerCombo()).thenReturn(20);
        when(poolReplenishmentService.isReplenishing(any(), any())).thenReturn(false);
    }

    @Test
    void index_should_RenderDefaultRaceGenderAndSourceResults_When_PageLoads() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Adrie");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));

        mockMvc.perform(withOwner(get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Adrie")));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.CURATED));
    }

    /**
     * Viewing is public as of slice 7 (see docs/DECISIONS.md, WebSecurityConfig): GET / is
     * permitAll at the filter chain, and CurrentIdentityArgumentResolver resolves an anonymous
     * Identity rather than throwing, so an anonymous visitor gets a normal 200 render instead
     * of a forced redirect to /login.
     */
    @Test
    void index_should_Return2xx_When_Anonymous() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Adrie");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));

        mockMvc.perform(withSession(get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Adrie")));
    }

    @Test
    void index_should_RenderFavoriteAndReportAsPreDisabled_When_OwnerAlreadyActedOnAName() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getId()).thenReturn(1L);
        when(curatedName.getDisplayName()).thenReturn("Adrie");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));
        when(favoriteService.getFavoritedNameIds(IDENTITY)).thenReturn(Set.of(1L));
        when(nameReportService.getReportedNameIds(IDENTITY)).thenReturn(Set.of(1L));

        mockMvc.perform(withOwner(get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Favorited")))
                .andExpect(content().string(containsString("Reported")))
                .andExpect(content().string(containsString("disabled")));
    }

    /**
     * Slice 8 (see docs/DECISIONS.md): the favorite/report icon buttons and the generate-more
     * CTA are all sec:authorize="isAuthenticated()" in index.html, so an anonymous render omits
     * all three and shows the "log in to do more" prompt in their place instead. This is
     * UI-layer defense-in-depth over the route-level/@PreAuthorize enforcement those endpoints
     * already had as of slice 7 -- see generateMore_should_Return401WithHxRedirect_When_AnonymousHtmxRequest
     * for proof the server-side rejection is untouched by this template-only change.
     */
    @Test
    void index_should_OmitActionButtons_When_Anonymous() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getId()).thenReturn(1L);
        when(aiName.getDisplayName()).thenReturn("Adrie");
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName));

        mockMvc.perform(withSession(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Favourite"))))
                .andExpect(content().string(not(containsString("Report"))))
                .andExpect(content().string(not(containsString("Generate"))))
                .andExpect(content().string(containsString("Log in")))
                .andExpect(content().string(containsString("/login")));
    }

    /**
     * Companion to index_should_OmitActionButtons_When_Anonymous: an authenticated render shows
     * the favorite/report buttons and (given an AI-source combo below its pool cap) the
     * generate-more CTA, and omits the anonymous-only login prompt.
     */
    @Test
    void index_should_RenderActionButtons_When_Authenticated() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getId()).thenReturn(1L);
        when(aiName.getDisplayName()).thenReturn("Adrie");
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName));

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Favourite")))
                .andExpect(content().string(containsString("Report")))
                .andExpect(content().string(containsString("Generate")))
                .andExpect(content().string(not(containsString("Log in <em>to favorite"))));
    }

    @Test
    void browse_should_RenderNamesForRequestedRaceAndGender_When_ParamsAreValid() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Argran");
        when(nameService.getNames(Race.HALF_ORC, Gender.MASCULINE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));

        mockMvc.perform(withOwner(get("/browse").param("race", "HALF_ORC").param("gender", "MASCULINE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Argran")));

        verify(nameService).getNames(eq(Race.HALF_ORC), eq(Gender.MASCULINE), eq(NameSourceFilter.CURATED));
    }

    @Test
    void browse_should_PassThroughRequestedSource_When_SourceParamIsGiven() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getDisplayName()).thenReturn("Sylvaine");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.BOTH))
                .thenReturn(List.of(aiName));

        mockMvc.perform(
                        withOwner(get("/browse").param("race", "ELF").param("gender", "FEMININE").param("source", "BOTH")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sylvaine")));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.BOTH));
    }

    @Test
    void browse_should_RenderEmptyMessage_When_NoNamesExistForSelection() throws Exception {
        when(nameService.getNames(Race.HUMAN, Gender.MASCULINE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(withOwner(get("/browse").param("race", "HUMAN").param("gender", "MASCULINE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No names yet for this race/gender/source")));
    }

    /**
     * Same as index_should_Return2xx_When_Anonymous, for the htmx fragment endpoint -- GET
     * /browse is also permitAll as of slice 7 (see docs/DECISIONS.md, WebSecurityConfig).
     */
    @Test
    void browse_should_Return2xx_When_Anonymous() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Argran");
        when(nameService.getNames(Race.HALF_ORC, Gender.MASCULINE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));

        mockMvc.perform(withSession(get("/browse").param("race", "HALF_ORC").param("gender", "MASCULINE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Argran")));
    }

    @Test
    void browse_should_ReturnBadRequest_When_SourceIsInvalid() throws Exception {
        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "NOT_A_REAL_SOURCE")))
                .andExpect(status().isBadRequest());
    }

    /**
     * Companion to browse_should_ReturnBadRequest_When_SourceIsInvalid: anonymous callers get
     * the same validation behavior as authenticated ones now that GET /browse is public. Does
     * NOT, by itself, cover the review finding on PR #72 about the container's ERROR-dispatched
     * forward to /error -- MockMvc's DispatcherServlet resolves MissingServletRequestParameterException
     * straight to a 400 response without a real servlet container's forward-to-/error, so this
     * request never re-enters the filter chain as DispatcherType.ERROR the way it would running
     * behind an actual embedded Tomcat. The .dispatcherTypeMatchers(DispatcherType.ERROR)
     * .permitAll() fix in WebSecurityConfig is Spring Security's documented remediation for
     * exactly that scenario; this repo has no @SpringBootTest(webEnvironment = RANDOM_PORT)
     * infrastructure to exercise the real container's error-page forward, so that fix is
     * unverified by an automated test here.
     */
    @Test
    void browse_should_ReturnBadRequest_When_AnonymousAndRequiredParamsAreMissing() throws Exception {
        mockMvc.perform(withSession(get("/browse"))).andExpect(status().isBadRequest());
    }

    @Test
    void browse_should_ShowGenerateMoreButton_When_AiPoolIsBelowCapAndNotGenerating() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName));

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Generate")));
    }

    @Test
    void browse_should_HideGenerateMoreButton_When_SourceIsCuratedOnly() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(withOwner(get("/browse")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Generate"))));
    }

    @Test
    void browse_should_HideGenerateMoreButton_When_AiPoolIsAtCap() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName, aiName));
        when(poolReplenishmentService.getPoolCapPerCombo()).thenReturn(2);

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Generate"))));
    }

    @Test
    void browse_should_ShowGeneratingIndicatorInsteadOfButton_When_ReplenishmentIsInFlight() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName));
        when(poolReplenishmentService.isReplenishing(Race.ELF, Gender.FEMININE)).thenReturn(true);

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Conjuring")))
                .andExpect(content().string(not(containsString("Generate"))));
    }

    @Test
    void generateMore_should_TriggerReplenishAndRenderBrowserFragment_When_Posted() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED)).thenReturn(List.of());

        mockMvc.perform(withOwner(post("/browse/generate-more")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk());

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
    }

    /**
     * replenish(...) is @Async, so isReplenishing(...) reading the in-flight map
     * immediately afterward in the same request thread would race the executor
     * thread's own update to that map -- stubbing it false here (as it genuinely
     * would be, this soon) and still asserting the polling indicator renders proves
     * generateMore doesn't rely on that racy read for its own response.
     */
    @Test
    void generateMore_should_ShowGeneratingIndicator_When_IsReplenishingHasNotYetFlippedTrue() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED)).thenReturn(List.of());
        when(poolReplenishmentService.isReplenishing(Race.ELF, Gender.FEMININE)).thenReturn(false);

        mockMvc.perform(withOwner(post("/browse/generate-more")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Conjuring")))
                .andExpect(content().string(not(containsString("Generate"))));
    }

    /**
     * The htmx-aware piece of slice 7 (see docs/DECISIONS.md, HtmxAuthenticationEntryPoint):
     * an anonymous htmx POST must not have the login page's HTML swapped into #browser, so an
     * HX-Request header on a request to a route requiring authentication gets a bare 401 with
     * an HX-Redirect header instead of the normal 302 -- htmx performs a full-page navigation to
     * /login off that header rather than swapping the (401, non-HTML) response body in place.
     */
    @Test
    void generateMore_should_Return401WithHxRedirect_When_AnonymousHtmxRequest() throws Exception {
        mockMvc.perform(withSession(post("/browse/generate-more")
                        .header("HX-Request", "true")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));

        verify(poolReplenishmentService, never()).replenish(any(), any());
    }
}
