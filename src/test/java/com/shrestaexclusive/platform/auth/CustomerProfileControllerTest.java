package com.shrestaexclusive.platform.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerProfileController.class)
class CustomerProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerAuthService service;

    @Test
    void readsProfileFromBearerSession() throws Exception {
        when(service.profile("session-token")).thenReturn(new CustomerProfileResponse(
                "11111111-1111-1111-1111-111111111111",
                "testuser@gmail.com",
                "SHRESTA UAT Test User",
                "ACTIVE",
                Instant.parse("2026-07-06T01:30:00Z")
        ));

        mockMvc.perform(get("/api/v1/customer/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer session-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                .andExpect(jsonPath("$.data.identityEmail").value("testuser@gmail.com"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void rejectsMissingSession() throws Exception {
        mockMvc.perform(get("/api/v1/customer/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_UNAUTHORIZED"));
    }
}
