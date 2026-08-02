package com.shrestaexclusive.platform.storefront.stores;

import static org.assertj.core.api.Assertions.assertThat;

import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.OpeningHourRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.StoreRow;
import com.shrestaexclusive.platform.testsupport.ImmediateKvReadThroughCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorefrontStoresServiceTest {

    @Test
    void derivesFiltersFromActiveStoreRows() {
        StorefrontStoresService service = new StorefrontStoresService(new StubRepository(), new ImmediateKvReadThroughCache());

        StorefrontStoresResponse response = service.getStores();

        assertThat(response.section().title()).isEqualTo("Store Locator");
        assertThat(response.stores()).hasSize(2);
        assertThat(response.cities()).containsExactly("Bengaluru", "Chennai");
        assertThat(response.states()).containsExactly("Karnataka", "Tamil Nadu");
        assertThat(response.serviceModes()).containsExactly("appointment", "pickup", "same_day_delivery");
        assertThat(response.stores().getFirst().fulfillment().deliveryRadiusKm()).isEqualTo(12);
    }

    private static final class StubRepository implements StorefrontStoresRepository {

        @Override
        public Optional<SectionRow> findActiveSection(String sectionKey) {
            return Optional.of(new SectionRow(UUID.randomUUID(), sectionKey, "Network", "Store Locator", "Find stores.", Map.of("serviceNote", "DB-owned")));
        }

        @Override
        public List<StoreRow> findActiveStores() {
            return List.of(
                    store("bengaluru-premium-hub", "Bengaluru", "Karnataka", List.of("same_day_delivery", "pickup", "appointment"), 10),
                    store("chennai-silk-studio", "Chennai", "Tamil Nadu", List.of("same_day_delivery", "appointment"), 20)
            );
        }

        private StoreRow store(String key, String city, String state, List<String> serviceModes, int sortOrder) {
            return new StoreRow(
                    UUID.randomUUID(),
                    key,
                    "SHRESTA " + city,
                    city,
                    "ACTIVE",
                    "Address 1",
                    "Address 2",
                    "Locality",
                    city,
                    state,
                    "560001",
                    "IN",
                    "+91-80-4567-1100",
                    "+91-90080-11000",
                    city.toLowerCase() + "@shrestaexclusive.com",
                    BigDecimal.valueOf(12.9D),
                    BigDecimal.valueOf(77.6D),
                    List.of("silk_saree"),
                    serviceModes,
                    List.of("Personal consultation"),
                    List.of(new OpeningHourRow("Mon-Sat", "10:00", "20:00", false)),
                    Map.of("deliveryRadiusKm", 12, "sameDayAvailable", true, "appointmentRequired", false, "deliveryPromise", "90 min", "pickupPromise", "45 min"),
                    sortOrder
            );
        }
    }
}
