package com.shrestaexclusive.platform.category.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryConfigController.class)
class CategoryConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryConfigService service;

    @Test
    void returnsCategoryConfigurationEnvelope() throws Exception {
        when(service.listActiveCategoryFamilies()).thenReturn(List.of(new CategoryFamilyResponse(
                "silk_saree",
                "Silk Saree",
                "Premium saree launch family",
                10,
                Map.of("launch", true),
                List.of(new CategoryFamilyResponse.ProductType("kanchipuram_saree", "Kanchipuram Saree", 10, Map.of())),
                List.of(new CategoryFamilyResponse.Attribute(
                        "weave",
                        "Weave",
                        "enum",
                        true,
                        true,
                        true,
                        List.of("kanchipuram", "banarasi"),
                        10
                )),
                List.of(new CategoryFamilyResponse.Filter(
                        "weave",
                        "Weave",
                        "weave",
                        "checkbox",
                        "attribute_facets.weave",
                        10
                )),
                List.of(new CategoryFamilyResponse.Tax("7113", 300, LocalDate.of(2026, 7, 5), null)),
                List.of(new CategoryFamilyResponse.Styling(
                        "wedding",
                        "Wedding",
                        List.of("silk_saree"),
                        Map.of("bundle", true),
                        10
                ))
        )));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.data[0].familyKey").value("silk_saree"))
                                .andExpect(jsonPath("$.data[0].productTypes[0].typeKey").value("kanchipuram_saree"))
                                .andExpect(jsonPath("$.data[0].attributes[0].allowedValues[1]").value("banarasi"))
                                .andExpect(jsonPath("$.data[0].filters[0].backendMapping").value("attribute_facets.weave"))
                .andExpect(jsonPath("$.data[0].taxes[0].gstRateBasisPoints").value(300))
                .andExpect(jsonPath("$.data[0].styling[0].rules.bundle").value(true));
    }
}
