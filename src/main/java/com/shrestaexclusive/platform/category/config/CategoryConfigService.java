package com.shrestaexclusive.platform.category.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.AttributeRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.FamilyRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.FilterRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.ProductTypeRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.StylingRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.TaxRow;
import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CategoryConfigService {

    public static final List<String> CATEGORY_TABLES = List.of(
            "category_family_config",
            "category_product_type_config",
            "category_attribute_config",
            "category_filter_config",
            "category_tax_config",
            "category_styling_config"
    );
    private static final TypeReference<List<CategoryFamilyResponse>> CATEGORY_FAMILY_LIST = new TypeReference<>() {
    };

    private final CategoryConfigRepository repository;
    private final KvReadThroughCache kvCache;

    public CategoryConfigService(CategoryConfigRepository repository, KvReadThroughCache kvCache) {
        this.repository = repository;
        this.kvCache = kvCache;
    }

    @Transactional(readOnly = true)
    public List<CategoryFamilyResponse> listActiveCategoryFamilies() {
        return kvCache.getOrLoad("category-config", "active-families", CATEGORY_TABLES, CATEGORY_FAMILY_LIST, this::loadActiveCategoryFamiliesFromDb);
    }

    public void publishFreshActiveCategoryFamiliesToKv(List<CategoryFamilyResponse> families) {
        kvCache.invalidateTables(CATEGORY_TABLES);
        kvCache.putFresh("category-config", "active-families", CATEGORY_TABLES, families);
    }

    public List<CategoryFamilyResponse> refreshActiveCategoryFamiliesKv() {
        List<CategoryFamilyResponse> families = loadActiveCategoryFamiliesFromDb();
        publishAfterCommit(families);
        return families;
    }

    private void publishAfterCommit(List<CategoryFamilyResponse> families) {
        Runnable publisher = () -> publishFreshActiveCategoryFamiliesToKv(families);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.run();
                }
            });
        } else {
            publisher.run();
        }
    }

    private List<CategoryFamilyResponse> loadActiveCategoryFamiliesFromDb() {
        List<FamilyRow> families = repository.findActiveFamilies();
        Map<UUID, FamilyBuilder> builders = new LinkedHashMap<>();
        for (FamilyRow family : families) {
            builders.put(family.familyId(), new FamilyBuilder(family));
        }

        List<UUID> familyIds = List.copyOf(builders.keySet());
        repository.findActiveProductTypes(familyIds).forEach(row -> builders.get(row.familyId()).add(row));
        repository.findAttributes(familyIds).forEach(row -> builders.get(row.familyId()).add(row));
        repository.findActiveFilters(familyIds).forEach(row -> builders.get(row.familyId()).add(row));
        repository.findActiveTaxes(familyIds).forEach(row -> builders.get(row.familyId()).add(row));
        repository.findActiveStyling(familyIds).forEach(row -> builders.get(row.familyId()).add(row));

        return builders.values().stream()
                .map(FamilyBuilder::build)
                .toList();
    }

    private static final class FamilyBuilder {

        private final FamilyRow family;
        private final List<CategoryFamilyResponse.ProductType> productTypes = new ArrayList<>();
        private final List<CategoryFamilyResponse.Attribute> attributes = new ArrayList<>();
        private final List<CategoryFamilyResponse.Filter> filters = new ArrayList<>();
        private final List<CategoryFamilyResponse.Tax> taxes = new ArrayList<>();
        private final List<CategoryFamilyResponse.Styling> styling = new ArrayList<>();

        private FamilyBuilder(FamilyRow family) {
            this.family = family;
        }

        private void add(ProductTypeRow row) {
            productTypes.add(new CategoryFamilyResponse.ProductType(
                    row.typeKey(),
                    row.displayName(),
                    row.sortOrder(),
                    row.metadata()
            ));
        }

        private void add(AttributeRow row) {
            attributes.add(new CategoryFamilyResponse.Attribute(
                    row.attributeKey(),
                    row.displayName(),
                    row.dataType(),
                    row.required(),
                    row.filterable(),
                    row.searchable(),
                    row.allowedValues(),
                    row.sortOrder()
            ));
        }

        private void add(FilterRow row) {
            filters.add(new CategoryFamilyResponse.Filter(
                    row.filterKey(),
                    row.displayName(),
                    row.attributeKey(),
                    row.frontendControl(),
                    row.backendMapping(),
                    row.sortOrder()
            ));
        }

        private void add(TaxRow row) {
            taxes.add(new CategoryFamilyResponse.Tax(
                    row.hsnCode(),
                    row.gstRateBasisPoints(),
                    row.effectiveFrom(),
                    row.effectiveTo()
            ));
        }

        private void add(StylingRow row) {
            styling.add(new CategoryFamilyResponse.Styling(
                    row.occasionKey(),
                    row.displayName(),
                    row.complementaryFamilyKeys(),
                    row.rules(),
                    row.sortOrder()
            ));
        }

        private CategoryFamilyResponse build() {
            return new CategoryFamilyResponse(
                    family.familyKey(),
                    family.displayName(),
                    family.description(),
                    family.sortOrder(),
                    family.metadata(),
                    productTypes,
                    attributes,
                    filters,
                    taxes,
                    styling
            );
        }
    }
}
