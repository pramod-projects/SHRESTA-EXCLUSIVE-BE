package com.shrestaexclusive.platform.admin.changes;

import java.util.Locale;

/**
 * Media slot targeted by a governed storefront-product-media-link change request.
 * PRIMARY and GALLERY_1..GALLERY_4 hold product images; VIDEO holds the product video.
 */
public enum StorefrontProductMediaSlot {

    PRIMARY,
    GALLERY_1,
    GALLERY_2,
    GALLERY_3,
    GALLERY_4,
    VIDEO;

    public String targetMediaType() {
        return this == VIDEO ? "PRODUCT_VIDEO" : "PRODUCT_IMAGE";
    }

    public int gallerySlot() {
        return switch (this) {
            case GALLERY_1 -> 1;
            case GALLERY_2 -> 2;
            case GALLERY_3 -> 3;
            case GALLERY_4 -> 4;
            default -> throw new IllegalArgumentException("Slot " + name() + " is not a gallery slot");
        };
    }

    public static StorefrontProductMediaSlot parse(String value) {
        if (value != null && !value.isBlank()) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (StorefrontProductMediaSlot slot : values()) {
                if (slot.name().equals(normalized)) {
                    return slot;
                }
            }
        }
        throw new IllegalArgumentException(
                "slot must be one of PRIMARY, VIDEO, GALLERY_1, GALLERY_2, GALLERY_3, GALLERY_4");
    }
}
