package com.shrestaexclusive.platform.asset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminUnauthorizedException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAssetController.class)
class AdminAssetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssetService service;

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
    void searchesAssetsForAdminDashboard() throws Exception {
        when(service.search("silk", "silk_saree", "kanchipuram_saree", null, "READY", 0, 24))
                .thenReturn(new AssetSearchResponse(List.of(asset()), 0, 24, 1));

        mockMvc.perform(get("/api/v1/admin/assets")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .param("query", "silk")
                        .param("categoryFamilyKey", "silk_saree")
                        .param("categoryProductTypeKey", "kanchipuram_saree")
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].assetKey").value("hero-silk-saree-maroon-gold"))
                .andExpect(jsonPath("$.data.assets[0].variants[0].variantKey").value("thumbnail"));

        verify(accessGuard).requireRole(eq("secret"), eq("CHANGE_SUBMITTER"), any());
    }

    @Test
    void uploadsMultipleAssetsWithMetadata() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "silk.png",
                "image/png",
                new byte[]{1, 2, 3}
        );
        when(service.upload(any(), any())).thenReturn(List.of(asset()));

        mockMvc.perform(multipart("/api/v1/admin/assets")
                        .file(file)
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "asset-upload-1")
                        .param("categoryFamilyKey", "silk_saree")
                        .param("categoryProductTypeKey", "kanchipuram_saree")
                        .param("tags", "silk", "zari"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("READY"));
    }

    @Test
    void replacesExistingAssetImageWithIdempotency() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "replacement.png",
                "image/png",
                new byte[]{4, 5, 6}
        );
        when(service.replaceImage(eq("hero-silk-saree-maroon-gold"), any())).thenReturn(asset());

        mockMvc.perform(multipart("/api/v1/admin/assets/hero-silk-saree-maroon-gold/image")
                        .file(file)
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "asset-replace-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetKey").value("hero-silk-saree-maroon-gold"));

        verify(service).replaceImage(eq("hero-silk-saree-maroon-gold"), any());
    }

    @Test
    void updatesAllEditableAssetMetadataFields() throws Exception {
        when(service.updateMetadata(eq("hero-silk-saree-maroon-gold"), any())).thenReturn(asset());

        mockMvc.perform(patch("/api/v1/admin/assets/hero-silk-saree-maroon-gold")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "asset-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "altText": "Updated saree alt text",
                                  "categoryFamilyKey": "silk_saree",
                                  "categoryProductTypeKey": "kanchipuram_saree",
                                  "productSku": "SHRESTA-SILK-0001",
                                  "tags": ["silk", "zari", "homepage"],
                                  "seoTitle": "Premium Silk Saree",
                                  "seoDescription": "Updated SEO copy",
                                  "clearCategoryFamilyKey": false,
                                  "clearCategoryProductTypeKey": false,
                                  "clearProductSku": false,
                                  "clearTags": false,
                                  "clearSeoTitle": false,
                                  "clearSeoDescription": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void archivesAssetsAndBulkAssignsCategory() throws Exception {
        when(service.bulkAssignCategory(any())).thenReturn(new AssetSearchResponse(List.of(asset()), 0, 50, 1));

        mockMvc.perform(post("/api/v1/admin/assets/bulk/category-assignment")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "asset-bulk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assetKeys": ["hero-silk-saree-maroon-gold"],
                                  "categoryFamilyKey": "silk_saree",
                                  "categoryProductTypeKey": "kanchipuram_saree"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(delete("/api/v1/admin/assets/hero-silk-saree-maroon-gold")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "asset-archive-1"))
                .andExpect(status().isOk());

        verify(service).archive("hero-silk-saree-maroon-gold");
    }

    @Test
    void rejectsUnauthorizedAssetRequests() throws Exception {
        doThrow(new StorefrontAdminUnauthorizedException()).when(accessGuard).requireRole(eq(null), eq(null), any());

        mockMvc.perform(get("/api/v1/admin/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
    }

    private AssetResponse asset() {
        AssetVariantResponse variant = new AssetVariantResponse(
                "thumbnail",
                "jpg",
                160,
                160,
                12000,
                "http://localhost:9010/shresta-local-assets/variants/hero-silk-saree-maroon-gold/v1/160.jpg"
        );
        return new AssetResponse(
                "hero-silk-saree-maroon-gold",
                "silk-saree.png",
                "http://localhost:9010/shresta-local-assets/categories/silk-saree-maroon-gold.png",
                "Silk saree",
                "silk_saree",
                "kanchipuram_saree",
                "SHRESTA-SILK-0001",
                "READY",
                1,
                1154,
                1398,
                240000,
                "image/png",
                "s3-compatible-local",
                "data:image/jpeg;base64,abc",
                List.of("silk", "zari"),
                "Silk Saree",
                "SEO",
                List.of(variant),
                new AssetOptimizationStats(240000, 12000, 228000, 95, 1)
        );
    }
}
