package com.shrestaexclusive.platform.category.config;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryConfigController {

    private final CategoryConfigService service;

    public CategoryConfigController(CategoryConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CategoryFamilyResponse>> listCategories() {
        return ApiResponse.ok(service.listActiveCategoryFamilies(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
