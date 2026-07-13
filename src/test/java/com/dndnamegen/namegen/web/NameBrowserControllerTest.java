package com.dndnamegen.namegen.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
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
import com.dndnamegen.namegen.generation.QualityGateService;
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
import com.dndnamegen.namegen.user.UserService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
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

    @MockBean private QualityGateService qualityGateService;

    @MockBean private UserService userService;

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
     * A CURATED name mock carrying just a display name -- enough for the sort tests, which only
     * assert on the rendered order of the display names. CURATED (not the null default) keeps the
     * template's source-icon branch off the null path.
     */
    private static Name name(String displayName) {
        Name name = mock(Name.class);
        when(name.getDisplayName()).thenReturn(displayName);
        when(name.getSource()).thenReturn(NameSource.CURATED);
        return name;
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
        // Matches app.quality-gate.max-length's real default -- populateBrowser calls this on
        // every render for the submit-a-name form's maxlength attribute (review of #77).
        when(qualityGateService.getMaxLength()).thenReturn(30);
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
     * Issue #81: under the USER_SUBMITTED filter, each name renders with a "submitted by
     * &lt;username&gt;" byline. The controller resolves the submitter id on the name to a username
     * via UserService and hands the results to the template keyed by name id.
     */
    @Test
    void browse_should_RenderSubmitterUsername_When_SourceIsUserSubmitted() throws Exception {
        Name userSubmittedName = mock(Name.class);
        when(userSubmittedName.getId()).thenReturn(10L);
        when(userSubmittedName.getDisplayName()).thenReturn("Grishnakh");
        when(userSubmittedName.getSource()).thenReturn(NameSource.USER_SUBMITTED);
        when(userSubmittedName.getSubmitterId()).thenReturn(5L);
        when(nameService.getNames(Race.HALF_ORC, Gender.MASCULINE, NameSourceFilter.USER_SUBMITTED))
                .thenReturn(List.of(userSubmittedName));
        when(userService.usernamesByIds(Set.of(5L))).thenReturn(Map.of(5L, "gandalf"));

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "HALF_ORC")
                        .param("gender", "MASCULINE")
                        .param("source", "USER_SUBMITTED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Grishnakh")))
                .andExpect(content().string(containsString("submitted by gandalf")));

        verify(nameService).getNames(eq(Race.HALF_ORC), eq(Gender.MASCULINE), eq(NameSourceFilter.USER_SUBMITTED));
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
                .andExpect(content().string(not(containsString("submit-name-form"))))
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
                .andExpect(content().string(containsString("submit-name-form")))
                .andExpect(content().string(not(containsString("Log in <em>to favorite"))));
    }

    /**
     * Regression guard for review of #77: the submit form's maxlength must track
     * QualityGateService's real app.quality-gate.max-length, not a hardcoded value that could
     * silently drift from (or simply never have matched) the actual server-side gate.
     */
    @Test
    void index_should_RenderSubmitFormMaxlengthFromQualityGateConfig_When_Authenticated() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(List.of());
        when(qualityGateService.getMaxLength()).thenReturn(45);

        mockMvc.perform(withOwner(get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("maxlength=\"45\"")));
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
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.ALL))
                .thenReturn(List.of(aiName));

        mockMvc.perform(
                        withOwner(get("/browse").param("race", "ELF").param("gender", "FEMININE").param("source", "ALL")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sylvaine")));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.ALL));
    }

    /**
     * Issue #79: the A–Z toggle orders the result list by display name, case-insensitively --
     * "adrie" sorts ahead of "Baern" even though capital B is the lower codepoint, since
     * capitalisation is only a rendering detail. Deliberately fed to the controller out of order
     * (Caelynn, adrie, Baern) so the assertion proves the controller reordered them rather than
     * the mock happening to return them sorted.
     */
    @Test
    void browse_should_SortNamesAlphabeticallyCaseInsensitive_When_SortIsAToZ() throws Exception {
        List<Name> unordered = List.of(name("Caelynn"), name("adrie"), name("Baern"));
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(unordered);

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("sort", "A_TO_Z")))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("adrie", "Baern", "Caelynn")));
    }

    /** Issue #79: the Z–A toggle is the reverse ordering, still case-insensitive. */
    @Test
    void browse_should_SortNamesDescending_When_SortIsZToA() throws Exception {
        List<Name> unordered = List.of(name("Caelynn"), name("adrie"), name("Baern"));
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(unordered);

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("sort", "Z_TO_A")))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("Caelynn", "Baern", "adrie")));
    }

    /**
     * Issue #79: DEFAULT (the fallback when no sort param is sent) is a true no-op that preserves
     * whatever order the query returned, rather than silently imposing an alphabetical order.
     */
    @Test
    void browse_should_PreserveQueryOrder_When_SortIsDefault() throws Exception {
        List<Name> unordered = List.of(name("Caelynn"), name("adrie"), name("Baern"));
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(unordered);

        mockMvc.perform(withOwner(get("/browse").param("race", "ELF").param("gender", "FEMININE")))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("Caelynn", "adrie", "Baern")));
    }

    /** Issue #79: the Sort toggle renders as its own button group with the current choice highlighted. */
    @Test
    void browse_should_RenderSortToggleWithSelectionHighlighted_When_SortParamGiven() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("sort", "A_TO_Z")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Sort\"")))
                .andExpect(content().string(stringContainsInOrder(
                        "id=\"sort-A_TO_Z\"", "aria-pressed=\"true\"", "selected")));
    }

    /**
     * Issue #79: the sort selection survives a generate-more click -- the controller threads the
     * sort param through so the re-rendered fragment keeps the chosen ordering rather than
     * silently reverting to DEFAULT.
     */
    @Test
    void generateMore_should_HonorSort_When_SortParamGiven() throws Exception {
        List<Name> unordered = List.of(name("Caelynn"), name("adrie"), name("Baern"));
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED)).thenReturn(unordered);

        mockMvc.perform(withOwner(post("/browse/generate-more")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")
                        .param("sort", "A_TO_Z")))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("adrie", "Baern", "Caelynn")));

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
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

    /**
     * Issue #98 regression: a replenishment cycle in flight for a combo must NOT surface the
     * "Conjuring five more" polling indicator on a non-AI source view of that same combo.
     * isReplenishing is keyed by race+gender only, so before the source guard, viewing
     * "User submitted" (or Handbook, or All) while the AI tab was mid-generation for the same
     * combo lit up the indicator and started polling on a view that never grows the AI pool --
     * the exact screenshot in the issue. Neither the indicator nor the generate button belongs
     * anywhere but the AI source.
     */
    @Test
    void browse_should_NotShowGeneratingIndicator_When_ReplenishingButSourceIsUserSubmitted() throws Exception {
        Name userSubmittedName = mock(Name.class);
        // Non-null id so the template's submitterUsernames.containsKey(n.id) byline lookup doesn't
        // probe the (empty, immutable) map with a null key; submitterId left null, so the name has
        // no resolvable byline -- irrelevant to what this test asserts.
        when(userSubmittedName.getId()).thenReturn(10L);
        when(userSubmittedName.getSource()).thenReturn(NameSource.USER_SUBMITTED);
        when(userSubmittedName.getDisplayName()).thenReturn("Sarah");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.USER_SUBMITTED))
                .thenReturn(List.of(userSubmittedName));
        when(poolReplenishmentService.isReplenishing(Race.ELF, Gender.FEMININE)).thenReturn(true);

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "USER_SUBMITTED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sarah")))
                .andExpect(content().string(not(containsString("Conjuring"))))
                .andExpect(content().string(not(containsString("Generate"))));
    }

    /**
     * Issue #98: the ALL source includes AI names in what it serves but must not itself be able
     * to spawn generation, so the "Generate five more" button is scoped to the AI source alone
     * -- it does not render under ALL even with an AI pool below cap.
     */
    @Test
    void browse_should_HideGenerateMoreButton_When_SourceIsAll() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.ALL))
                .thenReturn(List.of(aiName));

        mockMvc.perform(withOwner(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "ALL")))
                .andExpect(status().isOk())
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
     * Issue #98 (review of PR #99): generation is an AI-source-only action, enforced server-side
     * rather than left to the UI. A crafted authenticated POST with a non-AI source (here ALL, but
     * the same holds for CURATED/USER_SUBMITTED) is rejected with 400 and never reaches
     * replenish(...), so it cannot fire an LLM call from a view that must not trigger generation.
     */
    @Test
    void generateMore_should_ReturnBadRequestAndNotReplenish_When_SourceIsNotAiGenerated() throws Exception {
        mockMvc.perform(withOwner(post("/browse/generate-more")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "ALL")))
                .andExpect(status().isBadRequest());

        verify(poolReplenishmentService, never()).replenish(any(), any());
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
