package com.shrestaexclusive.platform.asset;

import java.time.Instant;

public record ProductMediaReservationResponse(
        String productId,
        Instant expiresAt
) {
}
