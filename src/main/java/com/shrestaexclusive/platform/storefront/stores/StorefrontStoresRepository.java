package com.shrestaexclusive.platform.storefront.stores;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StorefrontStoresRepository {

    Optional<SectionRow> findActiveSection(String sectionKey);

    List<StoreRow> findActiveStores();

    record SectionRow(
            UUID id,
            String sectionKey,
            String eyebrow,
            String title,
            String description,
            Map<String, Object> metadata
    ) {
    }

    record StoreRow(
            UUID id,
            String storeKey,
            String displayName,
            String shortName,
            String status,
            String addressLine1,
            String addressLine2,
            String locality,
            String city,
            String state,
            String postalCode,
            String countryCode,
            String phone,
            String whatsappNumber,
            String email,
            BigDecimal latitude,
            BigDecimal longitude,
            List<String> supportedFamilyKeys,
            List<String> serviceModes,
            List<String> highlights,
            List<OpeningHourRow> openingHours,
            Map<String, Object> fulfillment,
            int sortOrder
    ) {

        public StoreRow {
            supportedFamilyKeys = List.copyOf(supportedFamilyKeys);
            serviceModes = List.copyOf(serviceModes);
            highlights = List.copyOf(highlights);
            openingHours = List.copyOf(openingHours);
        }
    }

    record OpeningHourRow(
            String day,
            String opensAt,
            String closesAt,
            boolean closed
    ) {
    }
}
