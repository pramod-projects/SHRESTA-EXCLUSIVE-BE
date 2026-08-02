package com.shrestaexclusive.platform.platform.health;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class HealthController {

    private final String architecture;
    private final String moneyUnit;
    private final String cartState;
    private final String paymentTruth;

    public HealthController(
            @Value("${shresta.platform.architecture}") String architecture,
            @Value("${shresta.platform.money-unit}") String moneyUnit,
            @Value("${shresta.platform.cart-state}") String cartState,
            @Value("${shresta.platform.payment-truth}") String paymentTruth
    ) {
        this.architecture = architecture;
        this.moneyUnit = moneyUnit;
        this.cartState = cartState;
        this.paymentTruth = paymentTruth;
    }

    @GetMapping("/health")
    public ApiResponse<HealthPayload> health() {
        String traceId = MDC.get("traceId");
        return ApiResponse.ok(new HealthPayload(
                "shresta-be",
                "UP",
                architecture,
                moneyUnit,
                cartState,
                paymentTruth,
                Map.of(
                        "categoryFoundation", "configuration-driven",
                        "eventTransport", "spring-after-commit-phase-1",
                        "databaseTruth", "postgresql",
                        "volatileState", "redis"
                ),
                Instant.now()
        ), traceId == null ? "not-set" : traceId);
    }

    public record HealthPayload(
            String service,
            String status,
            String architecture,
            String moneyUnit,
            String cartState,
            String paymentTruth,
            Map<String, String> invariants,
            Instant timestamp
    ) {
    }
}
