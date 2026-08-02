package com.shrestaexclusive.platform.category.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shrestaexclusive.platform.category.config.CategoryFamilyResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCategoryController.class)
class AdminCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminCategoryService service;

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
    void managesCategoryFamiliesAndSubcategoriesThroughAdminApi() throws Exception {
        when(service.list()).thenReturn(List.of(family()));
        when(service.createFamily(any())).thenReturn(List.of(family()));
        when(service.updateFamily(eq("silk_saree"), any())).thenReturn(List.of(family()));
        when(service.createProductType(eq("silk_saree"), any())).thenReturn(List.of(family()));
        when(service.updateProductType(eq("silk_saree"), eq("kanchipuram_saree"), any())).thenReturn(List.of(family()));
        when(service.archiveProductType("silk_saree", "kanchipuram_saree")).thenReturn(List.of(family()));
        when(service.createAttribute(eq("silk_saree"), any())).thenReturn(List.of(family()));
        when(service.updateAttribute(eq("silk_saree"), eq("zari_type"), any())).thenReturn(List.of(family()));
        when(service.archiveAttribute("silk_saree", "zari_type")).thenReturn(List.of(family()));
        when(service.createFilter(eq("silk_saree"), any())).thenReturn(List.of(family()));
        when(service.updateFilter(eq("silk_saree"), eq("zari_type"), any())).thenReturn(List.of(family()));
        when(service.archiveFilter("silk_saree", "zari_type")).thenReturn(List.of(family()));
        when(service.createTax(eq("silk_saree"), any())).thenReturn(List.of(family()));
        when(service.updateTax(eq("silk_saree"), eq("5007"), eq(LocalDate.parse("2026-07-05")), any())).thenReturn(List.of(family()));
        when(service.archiveTax("silk_saree", "5007", LocalDate.parse("2026-07-05"))).thenReturn(List.of(family()));
        when(service.createStyling(eq("silk_saree"), any())).thenReturn(List.of(family()));
        when(service.updateStyling(eq("silk_saree"), eq("wedding"), any())).thenReturn(List.of(family()));
        when(service.archiveStyling("silk_saree", "wedding")).thenReturn(List.of(family()));
        when(service.archiveFamily("silk_saree")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/catalog/categories")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].familyKey").value("silk_saree"));

        mockMvc.perform(post("/api/v1/admin/catalog/categories")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "familyKey": "home_decor",
                                  "displayName": "Home Decor",
                                  "description": "Premium decor",
                                  "sortOrder": 40,
                                  "metadata": { "launch": false }
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/catalog/categories/silk_saree")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Silk Sarees",
                                  "description": "Updated",
                                  "sortOrder": 25,
                                  "metadata": { "featured": true }
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/catalog/categories/silk_saree/subcategories")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "typeKey": "kanchipuram_saree",
                                  "displayName": "Kanchipuram Saree",
                                  "sortOrder": 10,
                                  "metadata": { "seoSlug": "kanchipuram" }
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/catalog/categories/silk_saree/subcategories/kanchipuram_saree")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Kanchipuram Silks",
                                  "sortOrder": 11,
                                  "metadata": { "seoSlug": "kanchipuram-silks" }
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/catalog/categories/silk_saree/subcategories/kanchipuram_saree")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/catalog/categories/silk_saree/attributes")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributeKey": "zari_type",
                                  "displayName": "Zari Type",
                                  "dataType": "enum",
                                  "required": false,
                                  "filterable": true,
                                  "searchable": true,
                                  "allowedValues": ["pure_zari", "tested_zari"],
                                  "sortOrder": 30
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/catalog/categories/silk_saree/attributes/zari_type")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Zari Work",
                                  "dataType": "enum",
                                  "required": false,
                                  "filterable": true,
                                  "searchable": true,
                                  "allowedValues": ["pure_zari", "tested_zari", "half_fine_zari"],
                                  "sortOrder": 31
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/catalog/categories/silk_saree/attributes/zari_type")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/catalog/categories/silk_saree/filters")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "filterKey": "zari_type",
                                  "displayName": "Zari Type",
                                  "attributeKey": "zari_type",
                                  "frontendControl": "checkbox",
                                  "backendMapping": "attribute_facets.zari_type",
                                  "sortOrder": 30
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/catalog/categories/silk_saree/filters/zari_type")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Zari",
                                  "attributeKey": "zari_type",
                                  "frontendControl": "checkbox",
                                  "backendMapping": "attribute_facets.zari_type",
                                  "sortOrder": 31
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/catalog/categories/silk_saree/filters/zari_type")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/catalog/categories/silk_saree/taxes")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "hsnCode": "5007",
                                  "gstRateBasisPoints": 500,
                                  "effectiveFrom": "2026-07-05",
                                  "effectiveTo": null
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/catalog/categories/silk_saree/taxes/5007/2026-07-05")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gstRateBasisPoints": 700,
                                  "clearEffectiveTo": true
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/catalog/categories/silk_saree/taxes/5007/2026-07-05")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/catalog/categories/silk_saree/styling")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "occasionKey": "wedding",
                                  "displayName": "Wedding",
                                  "complementaryFamilyKeys": ["silk_saree"],
                                  "rules": { "bundle": true },
                                  "sortOrder": 10
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/catalog/categories/silk_saree/styling/wedding")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Wedding Edit",
                                  "complementaryFamilyKeys": ["silk_saree"],
                                  "rules": { "bundle": true, "priority": "high" },
                                  "sortOrder": 11
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/catalog/categories/silk_saree/styling/wedding")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/catalog/categories/silk_saree")
                        .header(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "secret")
                        .header(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER"))
                .andExpect(status().isOk());

        verify(accessGuard, org.mockito.Mockito.atLeastOnce()).requireRole(eq("secret"), eq("CHANGE_SUBMITTER"), any());
    }

    private CategoryFamilyResponse family() {
        return new CategoryFamilyResponse(
                "silk_saree",
                "Silk Sarees",
                "Premium silk saree launch family",
                20,
                Map.of("launch", true),
                List.of(new CategoryFamilyResponse.ProductType("kanchipuram_saree", "Kanchipuram Saree", 10, Map.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
