package com.shrestaexclusive.platform.category.config;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CategoryFamilyResponse(
        String familyKey,
        String displayName,
        String description,
        int sortOrder,
        Map<String, Object> metadata,
        List<ProductType> productTypes,
        List<Attribute> attributes,
        List<Filter> filters,
        List<Tax> taxes,
        List<Styling> styling
) {

    public CategoryFamilyResponse {
        metadata = Map.copyOf(metadata);
        productTypes = List.copyOf(productTypes);
        attributes = List.copyOf(attributes);
        filters = List.copyOf(filters);
        taxes = List.copyOf(taxes);
        styling = List.copyOf(styling);
    }

    public record ProductType(
            String typeKey,
            String displayName,
            int sortOrder,
            Map<String, Object> metadata
    ) {

        public ProductType {
            metadata = Map.copyOf(metadata);
        }
    }

    public record Attribute(
            String attributeKey,
            String displayName,
            String dataType,
            boolean required,
            boolean filterable,
            boolean searchable,
            List<String> allowedValues,
            int sortOrder
    ) {

        public Attribute {
            allowedValues = List.copyOf(allowedValues);
        }
    }

    public record Filter(
            String filterKey,
            String displayName,
            String attributeKey,
            String frontendControl,
            String backendMapping,
            int sortOrder
    ) {
    }

    public record Tax(
            String hsnCode,
            int gstRateBasisPoints,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
    }

    public record Styling(
            String occasionKey,
            String displayName,
            List<String> complementaryFamilyKeys,
            Map<String, Object> rules,
            int sortOrder
    ) {

        public Styling {
            complementaryFamilyKeys = List.copyOf(complementaryFamilyKeys);
            rules = Map.copyOf(rules);
        }
    }
}
