package com.shrestaexclusive.platform.storefront.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeFixtures;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeSectionUpdateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StorefrontAdminController.class)
class StorefrontAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StorefrontHomeService service;

    @MockBean
    private StorefrontAdminAccessGuard accessGuard;

    @MockBean
    private IdempotentMutationCoordinator mutations;

    @BeforeEach
    void executeMutations() {
        when(mutations.run(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    void rejectsMissingAdminKey() throws Exception {
        doThrow(new StorefrontAdminUnauthorizedException()).when(accessGuard).requireAdminKey(null);

        mockMvc.perform(get("/api/v1/admin/storefront/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
    }

    @Test
    void updatesStorefrontItemsThroughAdminApi() throws Exception {
        when(service.updateItem(any(StorefrontHomeItemUpdateCommand.class))).thenReturn(StorefrontHomeFixtures.sampleHome());

        mockMvc.perform(patch("/api/v1/admin/storefront/home/items/collection-silk-sarees")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "storefront-item-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Silk Sarees",
                                  "metadata": { "itemCount": 32, "qualityBadges": ["Pure Silk"] },
                                  "media": {
                                    "assetUrl": "https://d111111abcdef8.cloudfront.net/categories/silk.webp",
                                    "altText": "Updated silk saree image",
                                    "widthPx": 1200,
                                    "heightPx": 1500,
                                    "deliveryMode": "cloudfront"
                                  }
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate, private"))
                .andExpect(jsonPath("$.success").value(true));

        verify(accessGuard).requireAdminKey("secret");
        verify(service).updateItem(any(StorefrontHomeItemUpdateCommand.class));
    }

    @Test
    void updatesStorefrontSectionsThroughAdminApi() throws Exception {
        when(service.updateSection(any(StorefrontHomeSectionUpdateCommand.class))).thenReturn(StorefrontHomeFixtures.sampleHome());

        mockMvc.perform(patch("/api/v1/admin/storefront/home/sections/featured_collections")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "storefront-section-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Shop by Family",
                                  "description": "Backend-managed section copy"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).updateSection(any(StorefrontHomeSectionUpdateCommand.class));
    }
}
