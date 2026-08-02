package com.shrestaexclusive.platform.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerAuthController.class)
class CustomerAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerAuthService service;

    @Test
    void logsInSeedCustomerWithSixDigitOtp() throws Exception {
        when(service.login(any(CustomerLoginRequest.class))).thenReturn(new CustomerLoginResponse(
                "11111111-1111-1111-1111-111111111111",
                "testuser@gmail.com",
                "SHRESTA UAT Test User",
                "DEV_UAT_OTP",
                Instant.parse("2026-07-05T13:30:00Z"),
                Instant.parse("2026-07-06T01:30:00Z"),
                "session-token"
        ));

        mockMvc.perform(post("/api/v1/auth/customer/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identity": "testuser@gmail.com",
                                  "otp": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.customerId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.data.identityEmail").value("testuser@gmail.com"))
                .andExpect(jsonPath("$.data.authMode").value("DEV_UAT_OTP"))
                .andExpect(jsonPath("$.data.sessionToken").value("session-token"));
    }

    @Test
    void rejectsInvalidOtpShape() throws Exception {
        mockMvc.perform(post("/api/v1/auth/customer/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identity": "testuser@gmail.com",
                                  "otp": "12345"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CUSTOMER_LOGIN"));
    }

    @Test
    void revokesCustomerSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/customer/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer session-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                .andExpect(jsonPath("$.success").value(true));
    }
}
