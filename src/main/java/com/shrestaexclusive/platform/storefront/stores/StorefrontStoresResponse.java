package com.shrestaexclusive.platform.storefront.stores;

import java.util.List;

public record StorefrontStoresResponse(
        SectionCopy section,
        List<StoreLocation> stores,
        List<String> cities,
        List<String> states,
        List<String> serviceModes
) {

    public StorefrontStoresResponse {
        stores = List.copyOf(stores);
        cities = List.copyOf(cities);
        states = List.copyOf(states);
        serviceModes = List.copyOf(serviceModes);
    }

    public record SectionCopy(
            String eyebrow,
            String title,
            String description,
            String serviceNote
    ) {
    }

    public record StoreLocation(
            String storeKey,
            String displayName,
            String shortName,
            String status,
            Address address,
            Coordinates coordinates,
            Contact contact,
            List<String> supportedFamilyKeys,
            List<String> serviceModes,
            List<String> highlights,
            List<OpeningHour> openingHours,
            Fulfillment fulfillment,
            int sortOrder
    ) {

        public StoreLocation {
            supportedFamilyKeys = List.copyOf(supportedFamilyKeys);
            serviceModes = List.copyOf(serviceModes);
            highlights = List.copyOf(highlights);
            openingHours = List.copyOf(openingHours);
        }
    }

    public record Address(
            String addressLine1,
            String addressLine2,
            String locality,
            String city,
            String state,
            String postalCode,
            String countryCode
    ) {
    }

    public record Coordinates(
            double latitude,
            double longitude
    ) {
    }

    public record Contact(
            String phone,
            String whatsappNumber,
            String email
    ) {
    }

    public record OpeningHour(
            String day,
            String opensAt,
            String closesAt,
            boolean closed
    ) {
    }

    public record Fulfillment(
            int deliveryRadiusKm,
            boolean sameDayAvailable,
            boolean appointmentRequired,
            String deliveryPromise,
            String pickupPromise
    ) {
    }
}
