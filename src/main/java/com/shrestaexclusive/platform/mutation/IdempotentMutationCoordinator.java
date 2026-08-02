package com.shrestaexclusive.platform.mutation;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.function.Supplier;

public interface IdempotentMutationCoordinator {

    String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    <T> T run(
            String scope,
            String submittedIdempotencyKey,
            String requestFingerprint,
            String lockKey,
            TypeReference<T> responseType,
            Supplier<T> mutation
    );
}
