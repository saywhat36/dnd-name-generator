package com.dndnamegen.namegen.name;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NameController.class)
class NameControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private NameService nameService;

    @Test
    void getNames_should_ReturnCuratedNames_When_RaceAndGenderAreValid() throws Exception {
        Name curatedName = mock(Name.class);
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED))
                .thenReturn(List.of(curatedName));
        when(curatedName.getId()).thenReturn(1L);
        when(curatedName.getDisplayName()).thenReturn("Aelric");

        mockMvc.perform(get("/names").param("race", "ELF").param("gender", "FEMININE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Aelric"));

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.CURATED));
    }

    @Test
    void getNames_should_DefaultToCurated_When_SourceParamIsOmitted() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.CURATED)).thenReturn(List.of());

        mockMvc.perform(get("/names").param("race", "ELF").param("gender", "FEMININE"))
                .andExpect(status().isOk());

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.CURATED));
    }

    @Test
    void getNames_should_PassThroughSourceParam_When_SourceIsBoth() throws Exception {
        when(nameService.getNames(Race.ELF, Gender.FEMININE, NameSourceFilter.BOTH)).thenReturn(List.of());

        mockMvc.perform(get("/names").param("race", "ELF").param("gender", "FEMININE").param("source", "BOTH"))
                .andExpect(status().isOk());

        verify(nameService).getNames(eq(Race.ELF), eq(Gender.FEMININE), eq(NameSourceFilter.BOTH));
    }

    @Test
    void getNames_should_ReturnBadRequest_When_RaceIsInvalid() throws Exception {
        mockMvc.perform(get("/names").param("race", "NOT_A_REAL_RACE").param("gender", "FEMININE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNames_should_ReturnBadRequest_When_SourceIsInvalid() throws Exception {
        mockMvc.perform(get("/names")
                        .param("race", "ELF")
                        .param("gender", "FEMININE")
                        .param("source", "NOT_A_REAL_SOURCE"))
                .andExpect(status().isBadRequest());
    }
}
