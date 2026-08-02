package com.shrestaexclusive.platform.category.config;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CategoryConfigRepository {

    List<FamilyRow> findActiveFamilies();

    List<ProductTypeRow> findActiveProductTypes(List<UUID> familyIds);

    List<AttributeRow> findAttributes(List<UUID> familyIds);

    List<FilterRow> findActiveFilters(List<UUID> familyIds);

    List<TaxRow> findActiveTaxes(List<UUID> familyIds);

    List<StylingRow> findActiveStyling(List<UUID> familyIds);

    record FamilyRow(
            UUID familyId,
            String familyKey,
            String displayName,
            String description,
            int sortOrder,
            Map<String, Object> metadata
    ) {
    }

    record ProductTypeRow(
            UUID familyId,
            String typeKey,
            String displayName,
            int sortOrder,
            Map<String, Object> metadata
    ) {
    }

    record AttributeRow(
            UUID familyId,
            String attributeKey,
            String displayName,
            String dataType,
            boolean required,
            boolean filterable,
            boolean searchable,
            List<String> allowedValues,
            int sortOrder
    ) {
    }

    record FilterRow(
            UUID familyId,
            String filterKey,
            String displayName,
            String attributeKey,
            String frontendControl,
            String backendMapping,
            int sortOrder
    ) {
    }

    record TaxRow(
            UUID familyId,
            String hsnCode,
            int gstRateBasisPoints,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
    }

    record StylingRow(
            UUID familyId,
            String occasionKey,
            String displayName,
            List<String> complementaryFamilyKeys,
            Map<String, Object> rules,
            int sortOrder
    ) {
    }
}
