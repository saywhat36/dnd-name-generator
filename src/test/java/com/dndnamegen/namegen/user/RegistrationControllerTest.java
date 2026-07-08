package com.dndnamegen.namegen.user;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WebSecurityConfig's filter chain is global (see NameBrowserControllerTest), so POSTs
 * here need `.with(csrf())` too, even though this controller isn't part of that slice.
 */
@WebMvcTest(RegistrationController.class)
class RegistrationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private UserService userService;

    @Test
    void showForm_should_RenderRegistrationPage_When_Get() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create an account")));
    }

    @Test
    void register_should_RedirectToLogin_When_RegistrationSucceeds() throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "Gandalf")
                        .param("password", "hunter2!!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        verify(userService).register("Gandalf", "hunter2!!");
    }

    @Test
    void register_should_RerenderWithError_When_UsernameIsTooShort() throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "ab")
                        .param("password", "hunter2!!"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Username must be between")));

        verify(userService, never()).register(any(), any());
    }

    @Test
    void register_should_RerenderWithError_When_UsernameHasDisallowedCharacters() throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "gandalf the grey!")
                        .param("password", "hunter2!!"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("letters, numbers, underscores, and hyphens")));

        verify(userService, never()).register(any(), any());
    }

    @Test
    void register_should_RerenderWithError_When_PasswordIsTooShort() throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "Gandalf")
                        .param("password", "short"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Password must be at least")));

        verify(userService, never()).register(any(), any());
    }

    @Test
    void register_should_RerenderWithError_When_UsernameIsAlreadyTaken() throws Exception {
        when(userService.register(eq("Gandalf"), eq("hunter2!!"))).thenThrow(new DuplicateUsernameException("Gandalf"));

        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "Gandalf")
                        .param("password", "hunter2!!"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("already taken")));
    }

    @Test
    void register_should_RejectMissingCsrfToken_When_Posted() throws Exception {
        mockMvc.perform(post("/register").param("username", "Gandalf").param("password", "hunter2!!"))
                .andExpect(status().isForbidden());

        verify(userService, never()).register(any(), any());
    }
}
