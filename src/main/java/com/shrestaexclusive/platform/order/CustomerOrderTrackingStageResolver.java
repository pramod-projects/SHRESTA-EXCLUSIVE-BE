package com.shrestaexclusive.platform.order;

import java.util.Map;
import java.util.Set;

final class CustomerOrderTrackingStageResolver {

    private static final Map<String, String> STAGE_LABEL = Map.of(
            "PAYMENT", "Payment",
            "ORDER_PLACED", "Order placed",
            "PACKED", "Packed",
            "ON_THE_WAY", "On the way",
            "DELIVERED", "Delivered",
            "CANCELLED", "Cancelled"
    );
    private static final Map<String, String> STAGE_MEANING = Map.of(
            "PAYMENT", "We are confirming your payment.",
            "ORDER_PLACED", "Payment is confirmed and your order has entered our processing queue.",
            "PACKED", "Your items are being packed and prepared for dispatch.",
            "ON_THE_WAY", "Your shipment is in transit to your delivery address.",
            "DELIVERED", "Your order has been delivered.",
            "CANCELLED", "This order was cancelled and will not move to delivery stages."
    );

    private CustomerOrderTrackingStageResolver() {
    }

    static TrackingStage resolve(String orderStatus, String paymentStatus, String fulfillmentStatus) {
        String normalizedOrderStatus = normalize(orderStatus);
        String normalizedPaymentStatus = normalize(paymentStatus);
        String normalizedFulfillmentStatus = normalize(fulfillmentStatus);

        int nonTerminalIndex = nonTerminalStageIndex(normalizedOrderStatus, normalizedPaymentStatus, normalizedFulfillmentStatus);
        boolean isCancelled = "CANCELLED".equals(normalizedOrderStatus) || "CANCELLED".equals(normalizedFulfillmentStatus);
        if (isCancelled) {
            return stage("CANCELLED", nonTerminalIndex, true);
        }

        String stageCode = switch (nonTerminalIndex) {
            case 4 -> "DELIVERED";
            case 3 -> "ON_THE_WAY";
            case 2 -> "PACKED";
            case 1 -> "ORDER_PLACED";
            default -> "PAYMENT";
        };
        boolean terminal = "DELIVERED".equals(stageCode);
        return stage(stageCode, nonTerminalIndex, terminal);
    }

    private static TrackingStage stage(String code, int index, boolean terminal) {
        return new TrackingStage(
                code,
                STAGE_LABEL.getOrDefault(code, code),
                Math.max(index, 0),
                STAGE_MEANING.getOrDefault(code, "Order status updated."),
                terminal
        );
    }

    private static int nonTerminalStageIndex(String orderStatus, String paymentStatus, String fulfillmentStatus) {
        if ("DELIVERED".equals(orderStatus) || "DELIVERED".equals(fulfillmentStatus)) {
            return 4;
        }

        int stageIndex = paymentStageIndex(orderStatus, paymentStatus);

        if (Set.of("OUT_FOR_DELIVERY", "READY_FOR_PICKUP").contains(orderStatus)
                || Set.of("READY", "SHIPPED").contains(fulfillmentStatus)) {
            return 3;
        }
        if (Set.of("CONFIRMED", "PACKING").contains(orderStatus)
                || Set.of("ALLOCATED", "PACKING").contains(fulfillmentStatus)) {
            return 2;
        }
        if (Set.of("PLACED", "PAYMENT_FAILED").contains(orderStatus) || "CAPTURED".equals(paymentStatus)) {
            stageIndex = Math.max(stageIndex, 1);
        }

        return stageIndex;
    }

    private static int paymentStageIndex(String orderStatus, String paymentStatus) {
        if ("PAYMENT_PENDING".equals(orderStatus)
                || Set.of("PENDING", "AUTHORIZED", "FAILED").contains(paymentStatus)) {
            return 0;
        }
        return "CAPTURED".equals(paymentStatus) || "REFUNDED".equals(paymentStatus) ? 1 : 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    record TrackingStage(
            String code,
            String label,
            int index,
            String meaning,
            boolean terminal
    ) {
    }
}