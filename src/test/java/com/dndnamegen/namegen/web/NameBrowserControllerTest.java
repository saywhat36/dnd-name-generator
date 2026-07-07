package com.dndnamegen.namegen.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.favorite.FavoriteService;
import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.report.NameReportService;
import com.dndnamegen.namegen.session.SessionIdFilter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(NameBrowserController.class)
class NameBrowserControllerTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";

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
        return builder.cookie(new Cookie(SessionIdFilter.COOKIE_NAME, SESSION_ID));
    }

    /**
     * Every render calls both id-lookup services regardless of whether the result list is
     * empty, so an unstubbed mock returning null would NPE inside the template's
     * favoritedNameIds.contains(...)/reportedNameIds.contains(...) calls. Defaults to "no
     * prior activity" for every test; the one test that cares about pre-disabled buttons
     * overrides this. Also defaults the pool cap high and "not currently generating" so
     * existing tests (none of which stub these) don't need to know about the new
     * generate-more feature's model attributes.
     */
    @BeforeEach
    void stubNoPriorSessionActivityByDefault() {
        when(favoriteService.getFavoritedNameIds(SESSION_ID)).thenReturn(Set.of());
        when(nameReportService.getReportedNameIds(SESSION_ID)).thenReturn(Set.of());
        when(poolReplenishmentService.getPoolCapPerCombo()).thenReturn(20);
        when(poolReplenishmentService.isReplenishing(any(), any())).thenReturn(false);
    }

    @Test
    void index_should_RenderDefaultRaceGenderAndSourceResults_When_PageLoads() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Adrie");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));

        mockMvc.perform(withSession(get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Adrie")));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.CURATED));
    }

    @Test
    void index_should_RenderFavoriteAndReportAsPreDisabled_When_SessionAlreadyActedOnAName() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getId()).thenReturn(1L);
        when(curatedName.getDisplayName()).thenReturn("Adrie");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));
        when(favoriteService.getFavoritedNameIds(SESSION_ID)).thenReturn(Set.of(1L));
        when(nameReportService.getReportedNameIds(SESSION_ID)).thenReturn(Set.of(1L));

        mockMvc.perform(withSession(get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Favorited")))
                .andExpect(content().string(containsString("Reported")))
                .andExpect(content().string(containsString("disabled")));
    }

    @Test
    void browse_should_RenderNamesForRequestedRaceAndGender_When_ParamsAreValid() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Argran");
        when(nameService.getNames(Race.HALF_ORC, Gender.MASCULINE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));

        mockMvc.perform(withSession(get("/browse").param("race", "HALF_ORC").param("gender", "MASCULINE")))
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
                        withSession(get("/browse").param("race", "ELF").param("gender", "FEMININE").param("source", "BOTH")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sylvaine")));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.BOTH));
    }

    @Test
    void browse_should_RenderEmptyMessage_When_NoNamesExistForSelection() throws Exception {
        when(nameService.getNames(Race.HUMAN, Gender.MASCULINE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(withSession(get("/browse").param("race", "HUMAN").param("gender", "MASCULINE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No names yet for this race/gender/source")));
    }

    @Test
    void browse_should_ReturnBadRequest_When_SourceIsInvalid() throws Exception {
        mockMvc.perform(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "NOT_A_REAL_SOURCE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browse_should_ShowGenerateMoreButton_When_AiPoolIsBelowCapAndNotGenerating() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName));

        mockMvc.perform(withSession(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Generate 5 more AI names")));
    }

    @Test
    void browse_should_HideGenerateMoreButton_When_SourceIsCuratedOnly() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(withSession(get("/browse")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Generate 5 more AI names"))));
    }

    @Test
    void browse_should_HideGenerateMoreButton_When_AiPoolIsAtCap() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName, aiName));
        when(poolReplenishmentService.getPoolCapPerCombo()).thenReturn(2);

        mockMvc.perform(withSession(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Generate 5 more AI names"))));
    }

    @Test
    void browse_should_ShowGeneratingIndicatorInsteadOfButton_When_ReplenishmentIsInFlight() throws Exception {
        Name aiName = mock(Name.class);
        when(aiName.getSource()).thenReturn(NameSource.AI_GENERATED);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED))
                .thenReturn(List.of(aiName));
        when(poolReplenishmentService.isReplenishing(Race.ELF, Gender.FEMININE)).thenReturn(true);

        mockMvc.perform(withSession(get("/browse")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Generating more AI names")))
                .andExpect(content().string(not(containsString("Generate 5 more AI names"))));
    }

    @Test
    void generateMore_should_TriggerReplenishAndRenderBrowserFragment_When_Posted() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.AI_GENERATED)).thenReturn(List.of());

        mockMvc.perform(withSession(post("/browse/generate-more")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "AI_GENERATED")))
                .andExpect(status().isOk());

        verify(poolReplenishmentService).replenish(Race.ELF, Gender.FEMININE);
    }
}
