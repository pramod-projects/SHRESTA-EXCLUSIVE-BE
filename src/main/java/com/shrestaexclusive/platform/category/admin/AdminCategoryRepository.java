package com.shrestaexclusive.platform.category.admin;

interface AdminCategoryRepository {

    void createFamily(CategoryFamilyMutationRequest request);

    void updateFamily(String familyKey, CategoryFamilyMutationRequest request);

    void archiveFamily(String familyKey);

    void deleteFamily(String familyKey);

    void createProductType(String familyKey, CategoryProductTypeMutationRequest request);

    void updateProductType(String familyKey, String typeKey, CategoryProductTypeMutationRequest request);

    void archiveProductType(String familyKey, String typeKey);

    void deleteProductType(String familyKey, String typeKey);

    void createAttribute(String familyKey, CategoryAttributeMutationRequest request);

    void updateAttribute(String familyKey, String attributeKey, CategoryAttributeMutationRequest request);

    void archiveAttribute(String familyKey, String attributeKey);

    void deleteAttribute(String familyKey, String attributeKey);

    void createFilter(String familyKey, CategoryFilterMutationRequest request);

    void updateFilter(String familyKey, String filterKey, CategoryFilterMutationRequest request);

    void archiveFilter(String familyKey, String filterKey);

    void deleteFilter(String familyKey, String filterKey);

    void createTax(String familyKey, CategoryTaxMutationRequest request);

    void updateTax(String familyKey, String hsnCode, java.time.LocalDate effectiveFrom, CategoryTaxMutationRequest request);

    void archiveTax(String familyKey, String hsnCode, java.time.LocalDate effectiveFrom);

    void deleteTax(String familyKey, String hsnCode, java.time.LocalDate effectiveFrom);

    void createStyling(String familyKey, CategoryStylingMutationRequest request);

    void updateStyling(String familyKey, String occasionKey, CategoryStylingMutationRequest request);

    void archiveStyling(String familyKey, String occasionKey);

    void deleteStyling(String familyKey, String occasionKey);
}
