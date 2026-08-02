package com.shrestaexclusive.platform.storefront.stores;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StorefrontStoresController.class)
class StorefrontStoresControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StorefrontStoresService service;

    @Test
    void returnsBackendDrivenStoreLocatorPayload() throws Exception {
        when(service.getStores()).thenReturn(StorefrontStoresFixtures.sampleStores());

        mockMvc.perform(get("/api/v1/storefront/stores"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, must-revalidate, public"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.section.title").value("Store Locator"))
                .andExpect(jsonPath("$.data.stores[0].storeKey").value("bengaluru-premium-hub"))
                .andExpect(jsonPath("$.data.stores[0].address.city").value("Bengaluru"))
                .andExpect(jsonPath("$.data.serviceModes[0]").value("appointment"));
    }
}
