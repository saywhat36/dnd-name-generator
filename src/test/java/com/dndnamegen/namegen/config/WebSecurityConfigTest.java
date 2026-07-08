package com.dndnamegen.namegen.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.name.NameController;
import com.dndnamegen.namegen.name.NameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the slice 1 CSRF wiring is genuinely on: an existing POST endpoint (picked
 * arbitrarily -- any htmx-driven POST/DELETE route would do) rejects requests missing a
 * CSRF token and accepts requests carrying one, without any other observable behaviour
 * change from WebSecurityConfig.
 */
@WebMvcTest(NameController.class)
class WebSecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private NameService nameService;

    @Test
    void existingPostEndpoint_should_Return2xx_When_CsrfTokenPresent() throws Exception {
        when(nameService.flagName(1L)).thenReturn(true);

        mockMvc.perform(post("/names/1/flag").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void existingPostEndpoint_should_Return403_When_CsrfTokenMissing() throws Exception {
        mockMvc.perform(post("/names/1/flag")).andExpect(status().isForbidden());
    }
}
