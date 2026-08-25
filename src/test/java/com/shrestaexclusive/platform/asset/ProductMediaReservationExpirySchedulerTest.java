package com.shrestaexclusive.platform.asset;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductMediaReservationExpirySchedulerTest {

    @Test
    void displayCleanupStillRunsWhenProductCleanupFails() {
        AssetService assetService = mock(AssetService.class);
        when(assetService.expireAbandonedProductMediaReservations())
                .thenThrow(new MediaStorageException("R2 unavailable", new IllegalStateException("test")));
        ProductMediaReservationExpiryScheduler scheduler = new ProductMediaReservationExpiryScheduler(assetService);

        scheduler.expireAbandonedReservations();

        verify(assetService).expireAbandonedDisplayUploads();
    }
}