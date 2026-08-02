package com.shrestaexclusive.platform.storefront.stores;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.OpeningHourRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.StoreRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Address;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Contact;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Coordinates;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Fulfillment;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.OpeningHour;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.SectionCopy;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.StoreLocation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorefrontStoresService {

    public static final List<String> STOREFRONT_STORES_TABLES = List.of(
            "storefront_store_sections",
            "store_locations"
    );
    private static final TypeReference<StorefrontStoresResponse> STOREFRONT_STORES_RESPONSE = new TypeReference<>() {
    };
    private static final String STOREFRONT_STORES_CACHE_KEY = "active:v2";
    private static final String STORE_LOCATOR_SECTION_KEY = "store_locator";

    private final StorefrontStoresRepository repository;
    private final KvReadThroughCache kvCache;

    public StorefrontStoresService(StorefrontStoresRepository repository, KvReadThroughCache kvCache) {
        this.repository = repository;
        this.kvCache = kvCache;
    }

    @Transactional(readOnly = true)
    public StorefrontStoresResponse getStores() {
        return kvCache.getOrLoad("storefront-stores", STOREFRONT_STORES_CACHE_KEY, STOREFRONT_STORES_TABLES, STOREFRONT_STORES_RESPONSE, this::loadStoresFromDb);
    }

    private StorefrontStoresResponse loadStoresFromDb() {
        SectionRow section = repository.findActiveSection(STORE_LOCATOR_SECTION_KEY)
                .orElseThrow(() -> new IllegalStateException("Missing required storefront stores section: " + STORE_LOCATOR_SECTION_KEY));
        List<StoreLocation> stores = repository.findActiveStores().stream()
                .map(this::toStoreLocation)
                .toList();
        return new StorefrontStoresResponse(
                new SectionCopy(
                        section.eyebrow(),
                        section.title(),
                        section.description(),
                        string(section.metadata(), "serviceNote")
                ),
                stores,
                distinctSorted(stores.stream().map(store -> store.address().city()).toList()),
                distinctSorted(stores.stream().map(store -> store.address().state()).toList()),
                distinctSorted(stores.stream().flatMap(store -> store.serviceModes().stream()).toList())
        );
    }

    private StoreLocation toStoreLocation(StoreRow row) {
        return new StoreLocation(
                row.storeKey(),
                row.displayName(),
                row.shortName(),
                row.status(),
                new Address(
                        row.addressLine1(),
                        row.addressLine2(),
                        row.locality(),
                        row.city(),
                        row.state(),
                        row.postalCode(),
                        row.countryCode()
                ),
                new Coordinates(decimal(row.latitude()), decimal(row.longitude())),
                new Contact(row.phone(), row.whatsappNumber(), row.email()),
                row.supportedFamilyKeys(),
                row.serviceModes(),
                row.highlights(),
                row.openingHours().stream().map(this::toOpeningHour).toList(),
                new Fulfillment(
                        integer(row.fulfillment(), "deliveryRadiusKm"),
                        bool(row.fulfillment(), "sameDayAvailable"),
                        bool(row.fulfillment(), "appointmentRequired"),
                        string(row.fulfillment(), "deliveryPromise"),
                        string(row.fulfillment(), "pickupPromise")
                ),
                row.sortOrder()
        );
    }

    private OpeningHour toOpeningHour(OpeningHourRow row) {
        return new OpeningHour(row.day(), row.opensAt(), row.closesAt(), row.closed());
    }

    private List<String> distinctSorted(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private double decimal(BigDecimal value) {
        return value == null ? 0.0D : value.doubleValue();
    }

    private String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private boolean bool(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Boolean bool && bool;
    }
}
