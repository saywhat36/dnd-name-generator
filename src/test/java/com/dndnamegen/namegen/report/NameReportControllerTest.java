package com.dndnamegen.namegen.report;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.session.SessionIdFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(NameReportController.class)
class NameReportControllerTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired private MockMvc mockMvc;

    @MockBean private NameReportService nameReportService;

    @MockBean private NameRepository nameRepository;

    /**
     * @WebMvcTest auto-registers Filter beans, so the real SessionIdFilter runs in this
     * slice. Supplying a cookie in the filter's own format (a valid UUID) makes it pass this
     * session id through instead of minting its own random one -- see the regression fixed in
     * PR #37's FavoriteControllerTest for why setting the request attribute directly isn't
     * enough.
     */
    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        return builder.cookie(new Cookie(SessionIdFilter.COOKIE_NAME, SESSION_ID));
    }

    @Test
    void reportName_should_ReturnCreated_When_NameExists() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withSession(post("/reports").param("nameId", "1").param("reason", "not a real name")))
                .andExpect(status().isCreated());

        verify(nameReportService).reportName(SESSION_ID, 1L, "not a real name");
    }

    @Test
    void reportName_should_ReturnCreated_When_ReasonIsOmitted() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(withSession(post("/reports").param("nameId", "1")))
                .andExpect(status().isCreated());

        verify(nameReportService).reportName(SESSION_ID, 1L, null);
    }

    @Test
    void reportName_should_ReturnNotFound_When_NameIdDoesNotReferenceARealName() throws Exception {
        when(nameRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(withSession(post("/reports").param("nameId", "1")))
                .andExpect(status().isNotFound());
    }
}
