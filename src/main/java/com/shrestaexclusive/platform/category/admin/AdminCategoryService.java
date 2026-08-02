package com.shrestaexclusive.platform.category.admin;

import com.shrestaexclusive.platform.category.config.CategoryConfigService;
import com.shrestaexclusive.platform.category.config.CategoryFamilyResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCategoryService {

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
    public List<CategoryFamilyResponse> archiveFamily(String familyKey) {
        repository.archiveFamily(familyKey);
        return refreshKv();
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
