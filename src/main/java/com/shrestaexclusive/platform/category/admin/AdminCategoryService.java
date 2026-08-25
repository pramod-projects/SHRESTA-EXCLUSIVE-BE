package com.shrestaexclusive.platform.category.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shrestaexclusive.platform.category.config.CategoryConfigService;
import com.shrestaexclusive.platform.category.config.CategoryFamilyResponse;

@Service
public class AdminCategoryService {

    private static final Set<String> MERCHANDISING_ICONS = Set.of(
            "BadgeCheck", "Crown", "Flame", "Gem", "Heart", "Medal",
            "ShieldCheck", "ShoppingBag", "Sparkles", "Star", "Tag", "WandSparkles"
    );

    private final CategoryConfigService categoryConfigService;
    private final AdminCategoryRepository repository;

    public AdminCategoryService(CategoryConfigService categoryConfigService, AdminCategoryRepository repository) {
        this.categoryConfigService = categoryConfigService;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CategoryFamilyResponse> list() {
        return categoryConfigService.listActiveCategoryFamilies();
    }

    @Transactional
    public List<CategoryFamilyResponse> createFamily(CategoryFamilyMutationRequest request) {
        repository.createFamily(request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> updateFamily(String familyKey, CategoryFamilyMutationRequest request) {
        repository.updateFamily(familyKey, request);
        return refreshKv();
    }
        @Transactional
        public List<CategoryFamilyResponse> updateFamilyMerchandising(
            String familyKey,
            List<Map<String, Object>> merchandisingTags,
            List<Map<String, Object>> colorFilters
        ) {
            List<Map<String, Object>> normalizedTags = normalizeMerchandisingOptions(merchandisingTags, true);
            List<Map<String, Object>> normalizedColors = normalizeMerchandisingOptions(colorFilters, false);
            repository.updateFamilyMerchandising(familyKey, normalizedTags, normalizedColors);
        return refreshKv();
        }

    @Transactional
    public List<CategoryFamilyResponse> archiveFamily(String familyKey) {
        repository.archiveFamily(familyKey);
        return refreshKv();
    }

    @Transactional(readOnly = true)
    public void validateProductClassification(String familyKey, String productType) {
        CategoryFamilyResponse family = categoryConfigService.listActiveCategoryFamilies().stream()
                .filter(candidate -> candidate.familyKey().equals(familyKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active category family not found: " + familyKey));
        boolean validType = family.productTypes().stream().anyMatch(type -> type.typeKey().equals(productType));
        if (!validType) {
            throw new IllegalArgumentException("Product type does not belong to category family " + familyKey);
        }
    }

    private List<Map<String, Object>> normalizeMerchandisingOptions(List<Map<String, Object>> options, boolean withIcon) {
        if (options == null || options.size() > 50) {
            throw new IllegalArgumentException("Merchandising options must contain at most 50 entries");
        }
        Set<String> values = new java.util.HashSet<>();
        List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (Map<String, Object> option : options) {
            String value = requiredOptionText(option, "value");
            String label = requiredOptionText(option, "label");
            if (!value.matches("^[A-Z][A-Z0-9_]*$") || label.length() > 80 || !values.add(value)) {
                throw new IllegalArgumentException("Merchandising option values must be unique enum keys with labels up to 80 characters");
            }
            if (withIcon && (value.startsWith("COLOR_") || !MERCHANDISING_ICONS.contains(requiredOptionText(option, "icon")))) {
                throw new IllegalArgumentException("Product tag has an invalid value or icon");
            }
            if (!withIcon && !value.startsWith("COLOR_")) {
                throw new IllegalArgumentException("Colour filter values must start with COLOR_");
            }
            normalized.add(withIcon
                    ? Map.of("value", value, "label", label, "icon", requiredOptionText(option, "icon"))
                    : Map.of("value", value, "label", label));
        }
        return List.copyOf(normalized);
    }

    private String requiredOptionText(Map<String, Object> option, String key) {
        Object raw = option.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Merchandising option " + key + " is required");
        }
        return value.trim();
    }

    @Transactional
    public List<CategoryFamilyResponse> deleteFamily(String familyKey) {
        repository.deleteFamily(familyKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> createProductType(String familyKey, CategoryProductTypeMutationRequest request) {
        repository.createProductType(familyKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> updateProductType(String familyKey, String typeKey, CategoryProductTypeMutationRequest request) {
        repository.updateProductType(familyKey, typeKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> archiveProductType(String familyKey, String typeKey) {
        repository.archiveProductType(familyKey, typeKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> deleteProductType(String familyKey, String typeKey) {
        repository.deleteProductType(familyKey, typeKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> createAttribute(String familyKey, CategoryAttributeMutationRequest request) {
        repository.createAttribute(familyKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> updateAttribute(String familyKey, String attributeKey, CategoryAttributeMutationRequest request) {
        repository.updateAttribute(familyKey, attributeKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> archiveAttribute(String familyKey, String attributeKey) {
        repository.archiveAttribute(familyKey, attributeKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> deleteAttribute(String familyKey, String attributeKey) {
        repository.deleteAttribute(familyKey, attributeKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> createFilter(String familyKey, CategoryFilterMutationRequest request) {
        repository.createFilter(familyKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> updateFilter(String familyKey, String filterKey, CategoryFilterMutationRequest request) {
        repository.updateFilter(familyKey, filterKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> archiveFilter(String familyKey, String filterKey) {
        repository.archiveFilter(familyKey, filterKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> deleteFilter(String familyKey, String filterKey) {
        repository.deleteFilter(familyKey, filterKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> createTax(String familyKey, CategoryTaxMutationRequest request) {
        repository.createTax(familyKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> updateTax(String familyKey, String hsnCode, LocalDate effectiveFrom, CategoryTaxMutationRequest request) {
        repository.updateTax(familyKey, hsnCode, effectiveFrom, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> archiveTax(String familyKey, String hsnCode, LocalDate effectiveFrom) {
        repository.archiveTax(familyKey, hsnCode, effectiveFrom);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> deleteTax(String familyKey, String hsnCode, LocalDate effectiveFrom) {
        repository.deleteTax(familyKey, hsnCode, effectiveFrom);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> createStyling(String familyKey, CategoryStylingMutationRequest request) {
        repository.createStyling(familyKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> updateStyling(String familyKey, String occasionKey, CategoryStylingMutationRequest request) {
        repository.updateStyling(familyKey, occasionKey, request);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> archiveStyling(String familyKey, String occasionKey) {
        repository.archiveStyling(familyKey, occasionKey);
        return refreshKv();
    }

    @Transactional
    public List<CategoryFamilyResponse> deleteStyling(String familyKey, String occasionKey) {
        repository.deleteStyling(familyKey, occasionKey);
        return refreshKv();
    }

    private List<CategoryFamilyResponse> refreshKv() {
        return categoryConfigService.refreshActiveCategoryFamiliesKv();
    }
}
