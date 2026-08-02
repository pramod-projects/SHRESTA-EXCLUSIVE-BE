package com.shrestaexclusive.platform.storefront.stores;

import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Address;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Contact;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Coordinates;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.Fulfillment;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.OpeningHour;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.SectionCopy;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresResponse.StoreLocation;
import java.util.List;

final class StorefrontStoresFixtures {

    private StorefrontStoresFixtures() {
    }

    static StorefrontStoresResponse sampleStores() {
        StoreLocation store = new StoreLocation(
                "bengaluru-premium-hub",
                "SHRESTA EXCLUSIVE Bengaluru Premium Hub",
                "Bengaluru Hub",
                "ACTIVE",
                new Address("Level 2, Prestige Meridian", "Mahatma Gandhi Road", "Ashok Nagar", "Bengaluru", "Karnataka", "560001", "IN"),
                new Coordinates(12.9753D, 77.6033D),
                new Contact("+91-80-4567-1100", "+91-90080-11000", "bengaluru@shrestaexclusive.com"),
                List.of("silk_saree"),
                List.of("same_day_delivery", "pickup", "appointment"),
                List.of("Personal consultation", "Saree styling"),
                List.of(new OpeningHour("Mon-Sat", "10:00", "20:30", false)),
                new Fulfillment(12, true, false, "90-180 min", "45 min"),
                10
        );
        return new StorefrontStoresResponse(
                new SectionCopy("SHRESTA EXCLUSIVE service network", "Store Locator", "Find SHRESTA service coverage.", "DB-owned locator copy."),
                List.of(store),
                List.of("Bengaluru"),
                List.of("Karnataka"),
                List.of("appointment", "pickup", "same_day_delivery")
        );
    }
}
