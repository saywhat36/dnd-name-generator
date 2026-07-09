package com.dndnamegen.namegen.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.dndnamegen.namegen.user.DbUserDetailsService;
import com.dndnamegen.namegen.user.LoginController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 4: proves formLogin/logout are wired against WebSecurityConfig's auto-detected
 * DbUserDetailsService + PasswordEncoder pair. Mirrors WebSecurityConfigTest's pattern of
 * proving the global filter chain against a single @WebMvcTest slice -- LoginController is
 * arbitrary here (the same as NameController was for the CSRF slice-1 test), since formLogin/
 * logout are handled entirely by Spring Security's filters, not by any controller method.
 *
 * <p>The autowired PasswordEncoder is the real bean from WebSecurityConfig (already proven
 * present in this slice by WebSecurityConfigTest's passing CSRF assertions) -- used here only to
 * produce a matching hash for the mocked DbUserDetailsService to return, not exercised for its
 * own behavior.
 */
@WebMvcTest(LoginController.class)
class LoginLogoutTest {

    private static final String RAW_PASSWORD = "hunter2!!";

    @Autowired private MockMvc mockMvc;

    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private DbUserDetailsService userDetailsService;

    @Test
    void formLogin_should_Authenticate_When_CredentialsMatch() throws Exception {
        when(userDetailsService.loadUserByUsername("Gandalf")).thenReturn(gandalfWithPassword(RAW_PASSWORD));

        mockMvc.perform(formLogin().user("Gandalf").password(RAW_PASSWORD))
                .andExpect(authenticated().withUsername("Gandalf"));
    }

    @Test
    void formLogin_should_RejectLogin_When_PasswordWrong() throws Exception {
        when(userDetailsService.loadUserByUsername("Gandalf")).thenReturn(gandalfWithPassword(RAW_PASSWORD));

        mockMvc.perform(formLogin().user("Gandalf").password("wrong-password"))
                .andExpect(unauthenticated());
    }

    @Test
    void formLogin_should_RejectLogin_When_UsernameUnknown() throws Exception {
        when(userDetailsService.loadUserByUsername("nobody")).thenThrow(new UsernameNotFoundException("no account"));

        mockMvc.perform(formLogin().user("nobody").password(RAW_PASSWORD)).andExpect(unauthenticated());
    }

    @Test
    void logout_should_ClearAuthenticationAndRedirectToLoginWithLogoutParam_When_Posted() throws Exception {
        mockMvc.perform(logout())
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?logout"));
    }

    private UserDetails gandalfWithPassword(String rawPassword) {
        return User.withUsername("Gandalf")
                .password(passwordEncoder.encode(rawPassword))
                .authorities("ROLE_USER")
                .build();
    }
}
