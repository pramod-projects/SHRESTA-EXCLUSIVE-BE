package com.shrestaexclusive.platform.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProductMediaReservationExpiryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ProductMediaReservationExpiryScheduler.class);

    private final AssetService assetService;

    public ProductMediaReservationExpiryScheduler(AssetService assetService) {
        this.assetService = assetService;
    }

    @Scheduled(
            fixedDelayString = "${shresta.media.reservation-cleanup.fixed-delay-ms:900000}",
            initialDelayString = "${shresta.media.reservation-cleanup.initial-delay-ms:60000}"
    )
    public void expireAbandonedReservations() {
        try {
            int expired = assetService.expireAbandonedProductMediaReservations();
            if (expired > 0) {
                LOG.info("product-media-reservation-cleanup expired {} abandoned reservations", expired);
            }
        } catch (RuntimeException exception) {
            LOG.error("product-media-reservation-cleanup failed; retrying on the next schedule", exception);
        }
        try {
            int expiredDisplayUploads = assetService.expireAbandonedDisplayUploads();
            if (expiredDisplayUploads > 0) {
                LOG.info("display-media-upload-cleanup archived {} abandoned uploads", expiredDisplayUploads);
            }
        } catch (RuntimeException exception) {
            LOG.error("display-media-upload-cleanup failed; retrying on the next schedule", exception);
        }
    }
}