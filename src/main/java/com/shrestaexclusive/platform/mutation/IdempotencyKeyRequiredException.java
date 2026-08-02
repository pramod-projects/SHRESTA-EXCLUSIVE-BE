package com.shrestaexclusive.platform.mutation;

public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("Idempotency-Key header is required for mutating requests");
    }
}
