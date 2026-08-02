package com.shrestaexclusive.platform.storefront.home;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StorefrontHomeController.class)
class StorefrontHomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StorefrontHomeService service;

    @Test
    void returnsLightweightBackendDrivenStorefrontPayload() throws Exception {
        when(service.getHome()).thenReturn(StorefrontHomeFixtures.sampleHome());

        mockMvc.perform(get("/api/v1/storefront/home"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, must-revalidate, public"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.brand.name").value("SHRESTA EXCLUSIVE"))
                .andExpect(jsonPath("$.data.featuredCollectionsSection.title").value("Shop by Category"))
                .andExpect(jsonPath("$.data.featuredCollections[0].familyKey").value("silk_saree"))
                .andExpect(jsonPath("$.data.featuredCollections[0].image.url").value("http://localhost:9010/shresta-local-assets/categories/silk-saree-maroon-gold.png"));
    }
}
