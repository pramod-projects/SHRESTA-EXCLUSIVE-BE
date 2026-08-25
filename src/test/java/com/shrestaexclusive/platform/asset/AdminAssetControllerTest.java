package com.shrestaexclusive.platform.asset;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminUnauthorizedException;

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
        public void executeMutations() {
        when(mutations.run(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    void authorizesDirectUploadWithoutReceivingBytes() throws Exception {
        when(service.authorizeUpload(any(), eq("admin@example.com"))).thenReturn(new MediaUploadAuthorizationResponse(
                "550e8400-e29b-41d4-a716-446655440000",
                "media-550e8400e29b41d4a716446655440000",
                "products/product-one/images/550e8400-e29b-41d4-a716-446655440000.webp",
                "https://example.r2.cloudflarestorage.com/signed",
                Instant.parse("2026-07-05T00:10:00Z"),
                "image/webp",
                Map.of("content-type", "image/webp")
        ));

        mockMvc.perform(post("/api/v1/admin/assets/upload-authorizations")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header("X-SHRESTA-ADMIN-ACTOR", "admin@example.com")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "authorize-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": "product-one",
                                  "mediaType": "PRODUCT_IMAGE",
                                  "contentType": "image/webp",
                                  "originalFilename": "saree.webp",
                                  "byteSize": 120000,
                                  "widthPx": 1800,
                                  "heightPx": 2400
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaId").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.data.objectKey").value("products/product-one/images/550e8400-e29b-41d4-a716-446655440000.webp"))
                .andExpect(jsonPath("$.data.requiredHeaders.content-type").value("image/webp"));
    }

    @Test
    void completesOnlyThroughExplicitCompletionEndpoint() throws Exception {
        when(service.completeUpload("550e8400-e29b-41d4-a716-446655440000", "admin@example.com")).thenReturn(asset());

        mockMvc.perform(post("/api/v1/admin/assets/upload-completions")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header("X-SHRESTA-ADMIN-ACTOR", "admin@example.com")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "complete-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaId\":\"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        verify(service).completeUpload("550e8400-e29b-41d4-a716-446655440000", "admin@example.com");
    }

    @Test
    void searchesCanonicalAssets() throws Exception {
        when(service.search("silk", null, null, null, "READY", 0, 24))
                .thenReturn(new AssetSearchResponse(List.of(asset()), 0, 24, 1, 3, 2, 1, 0, 1, 2));

        mockMvc.perform(get("/api/v1/admin/assets")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .param("query", "silk")
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].assetKey").value("media-550e8400e29b41d4a716446655440000"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.systemTotal").value(3))
                .andExpect(jsonPath("$.data.systemImageTotal").value(2))
                .andExpect(jsonPath("$.data.systemVideoTotal").value(1))
                .andExpect(jsonPath("$.data.systemReferencedTotal").value(1))
                .andExpect(jsonPath("$.data.systemUnreferencedTotal").value(2));
    }

    @Test
    void listsStorefrontUnreferencedAssets() throws Exception {
        when(service.searchStorefrontUnreferenced(null, null, 0, 24))
                .thenReturn(new StorefrontUnreferencedAssetsResponse(List.of(asset()), 0, 24, 1));

        mockMvc.perform(get("/api/v1/admin/assets/storefront-unreferenced")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].assetKey").value("media-550e8400e29b41d4a716446655440000"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(24))
                .andExpect(jsonPath("$.data.total").value(1));

        verify(accessGuard).requireRole(eq("secret"), eq("CHANGE_SUBMITTER"), any());
    }

    @Test
    void rejectsUnauthorizedStorefrontUnreferencedRequests() throws Exception {
        doThrow(new StorefrontAdminUnauthorizedException()).when(accessGuard).requireRole(eq(null), eq(null), any());

        mockMvc.perform(get("/api/v1/admin/assets/storefront-unreferenced"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
    }

    @Test
    void rejectsUnauthorizedAssetRequests() throws Exception {
        doThrow(new StorefrontAdminUnauthorizedException()).when(accessGuard).requireRole(eq(null), eq(null), any());

        mockMvc.perform(get("/api/v1/admin/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
    }

    @Test
    void translatesStorageFailuresWithoutExposingProviderDetails() throws Exception {
        when(service.completeUpload("550e8400-e29b-41d4-a716-446655440000", "admin@example.com"))
                .thenThrow(new MediaStorageException("Media verification is temporarily unavailable", new RuntimeException("provider secret detail")));

        mockMvc.perform(post("/api/v1/admin/assets/upload-completions")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .header("X-SHRESTA-ADMIN-ACTOR", "admin@example.com")
                        .header(IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER, "complete-storage-failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaId\":\"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MEDIA_STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Media verification is temporarily unavailable"));
    }

    private AssetResponse asset() {
        return new AssetResponse(
                "media-550e8400e29b41d4a716446655440000",
                "saree.webp",
                "https://media.example.com/products/product-one/images/550e8400-e29b-41d4-a716-446655440000.webp",
                "Silk saree",
                null,
                null,
                "product-one",
                "READY",
                1,
                1800,
                2400,
                120000,
                "image/webp",
                "cloudflare-r2",
                List.of("SILK"),
                null,
                null
        );
    }
}
