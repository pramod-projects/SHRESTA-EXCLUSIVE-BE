package com.shrestaexclusive.platform.platform.health;

import java.time.Instant;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/platform")
public class HealthController {

    private final String architecture;
    private final String moneyUnit;
    private final String cartState;
    private final String paymentTruth;
        private final DeploymentMode environmentMode;

    public HealthController(
            @Value("${shresta.platform.architecture}") String architecture,
            @Value("${shresta.platform.money-unit}") String moneyUnit,
            @Value("${shresta.platform.cart-state}") String cartState,
            @Value("${shresta.platform.payment-truth}") String paymentTruth,
            @Value("${shresta.environment.mode}") DeploymentMode environmentMode
    ) {
        this.architecture = architecture;
        this.moneyUnit = moneyUnit;
        this.cartState = cartState;
        this.paymentTruth = paymentTruth;
        this.environmentMode = environmentMode;
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
                environmentMode,
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
                        DeploymentMode environmentMode,
            Map<String, String> invariants,
            Instant timestamp
    ) {
    }

        public enum DeploymentMode {
                DEV,
                UAT,
                PROD
        }
}
