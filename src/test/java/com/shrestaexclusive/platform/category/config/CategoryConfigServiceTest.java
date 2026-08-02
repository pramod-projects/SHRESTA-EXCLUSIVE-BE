package com.shrestaexclusive.platform.category.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.shrestaexclusive.platform.testsupport.ImmediateKvReadThroughCache;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.AttributeRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.FamilyRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.FilterRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.ProductTypeRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.StylingRow;
import com.shrestaexclusive.platform.category.config.CategoryConfigRepository.TaxRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryConfigServiceTest {

    @Test
    void assemblesCategoryConfigurationByFamily() {
        UUID sareeId = UUID.randomUUID();
        CategoryConfigService service = new CategoryConfigService(new StubRepository(sareeId), new ImmediateKvReadThroughCache());

        List<CategoryFamilyResponse> families = service.listActiveCategoryFamilies();

        assertThat(families).hasSize(1);
        CategoryFamilyResponse saree = families.getFirst();
        assertThat(saree.familyKey()).isEqualTo("silk_saree");
        assertThat(saree.productTypes()).extracting(CategoryFamilyResponse.ProductType::typeKey)
            .containsExactly("kanchipuram_saree");
        assertThat(saree.attributes()).extracting(CategoryFamilyResponse.Attribute::attributeKey)
            .containsExactly("weave");
        assertThat(saree.filters()).first()
                .extracting(CategoryFamilyResponse.Filter::backendMapping)
            .isEqualTo("attribute_facets.weave");
        assertThat(saree.taxes()).first()
                .extracting(CategoryFamilyResponse.Tax::gstRateBasisPoints)
                .isEqualTo(300);
        assertThat(saree.styling()).first()
                .extracting(CategoryFamilyResponse.Styling::occasionKey)
                .isEqualTo("wedding");
    }

    private static final class StubRepository implements CategoryConfigRepository {

        private final UUID familyId;

        private StubRepository(UUID familyId) {
            this.familyId = familyId;
        }

        @Override
        public List<FamilyRow> findActiveFamilies() {
            return List.of(new FamilyRow(
                    familyId,
                    "silk_saree",
                    "Silk Saree",
                    "Premium saree launch family",
                    10,
                    Map.of("launch", true)
            ));
        }

        @Override
        public List<ProductTypeRow> findActiveProductTypes(List<UUID> familyIds) {
            return List.of(new ProductTypeRow(familyId, "kanchipuram_saree", "Kanchipuram Saree", 10, Map.of()));
        }

        @Override
        public List<AttributeRow> findAttributes(List<UUID> familyIds) {
            return List.of(new AttributeRow(
                    familyId,
                    "weave",
                    "Weave",
                    "enum",
                    true,
                    true,
                    true,
                    List.of("kanchipuram", "banarasi"),
                    10
            ));
        }

        @Override
        public List<FilterRow> findActiveFilters(List<UUID> familyIds) {
            return List.of(new FilterRow(
                    familyId,
                    "weave",
                    "Weave",
                    "weave",
                    "checkbox",
                    "attribute_facets.weave",
                    10
            ));
        }

        @Override
        public List<TaxRow> findActiveTaxes(List<UUID> familyIds) {
            return List.of(new TaxRow(familyId, "7113", 300, LocalDate.of(2026, 7, 5), null));
        }

        @Override
        public List<StylingRow> findActiveStyling(List<UUID> familyIds) {
            return List.of(new StylingRow(
                    familyId,
                    "wedding",
                    "Wedding",
                    List.of("silk_saree"),
                    Map.of("bundle", true),
                    10
            ));
        }
    }
}
