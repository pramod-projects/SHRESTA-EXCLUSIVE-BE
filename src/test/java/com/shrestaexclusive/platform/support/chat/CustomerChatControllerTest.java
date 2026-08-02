package com.shrestaexclusive.platform.support.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerChatController.class)
class CustomerChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerChatService service;

    @Test
    void createsCustomerChatMessage() throws Exception {
        when(service.reply(any(CustomerChatMessageRequest.class))).thenReturn(new CustomerChatMessageResponse(
                "11111111-1111-1111-1111-111111111111",
                "Use Stores to view SHRESTA hubs.",
                List.of("Find stores"),
                false,
                Instant.parse("2026-07-05T14:30:00Z")
        ));

        mockMvc.perform(post("/api/v1/customer/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Find stores near me",
                                  "contextPath": "/stores"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conversationId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.data.quickActions[0]").value("Find stores"));
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/customer/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CUSTOMER_CHAT_MESSAGE"));
    }
}
