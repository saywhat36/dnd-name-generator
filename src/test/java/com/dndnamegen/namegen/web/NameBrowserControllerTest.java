package com.dndnamegen.namegen.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NameBrowserController.class)
class NameBrowserControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private NameService nameService;

    @Test
    void index_should_RenderDefaultRaceAndGenderResults_When_PageLoads() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Adrie");
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(List.of(curatedName));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Adrie")));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.CURATED));
    }

    @Test
    void browse_should_RenderNamesForRequestedRaceAndGender_When_ParamsAreValid() throws Exception {
        Name curatedName = mock(Name.class);
        when(curatedName.getDisplayName()).thenReturn("Argran");
        when(nameService.getNames(Race.HALF_ORC, Gender.MASCULINE, NameSourceFilter.CURATED)).thenReturn(List.of(curatedName));

        mockMvc.perform(get("/browse").param("race", "HALF_ORC").param("gender", "MASCULINE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Argran")));

        verify(nameService).getNames(eq(Race.HALF_ORC), eq(Gender.MASCULINE), eq(NameSourceFilter.CURATED));
    }

    @Test
    void browse_should_RenderEmptyMessage_When_NoCuratedNamesExist() throws Exception {
        when(nameService.getNames(Race.HUMAN, Gender.MASCULINE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(get("/browse").param("race", "HUMAN").param("gender", "MASCULINE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No curated names yet")));
    }
}
