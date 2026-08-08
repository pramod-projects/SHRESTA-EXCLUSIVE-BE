package com.shrestaexclusive.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CustomerOrderTrackingStageResolverTest {

    @Test
    void resolvesPaymentAsFirstStageWhenPaymentIsPending() {
        CustomerOrderTrackingStageResolver.TrackingStage stage = CustomerOrderTrackingStageResolver.resolve(
                "PAYMENT_PENDING",
                "PENDING",
                "PENDING"
        );

        assertThat(stage.code()).isEqualTo("PAYMENT");
        assertThat(stage.index()).isEqualTo(0);
    }

    @Test
    void resolvesOrderPlacedAfterPaymentCaptureBeforePacking() {
        CustomerOrderTrackingStageResolver.TrackingStage stage = CustomerOrderTrackingStageResolver.resolve(
                "PLACED",
                "CAPTURED",
                "PENDING"
        );

        assertThat(stage.code()).isEqualTo("ORDER_PLACED");
        assertThat(stage.index()).isEqualTo(1);
    }

    @Test
    void resolvesPackedFromOrderOrFulfillmentProgress() {
        CustomerOrderTrackingStageResolver.TrackingStage byOrderStage = CustomerOrderTrackingStageResolver.resolve(
                "PACKING",
                "CAPTURED",
                "PENDING"
        );
        CustomerOrderTrackingStageResolver.TrackingStage byFulfillmentStage = CustomerOrderTrackingStageResolver.resolve(
                "PLACED",
                "CAPTURED",
                "ALLOCATED"
        );

        assertThat(byOrderStage.code()).isEqualTo("PACKED");
        assertThat(byOrderStage.index()).isEqualTo(2);
        assertThat(byFulfillmentStage.code()).isEqualTo("PACKED");
        assertThat(byFulfillmentStage.index()).isEqualTo(2);
    }

    @Test
    void resolvesOnTheWayFromShipmentStates() {
        CustomerOrderTrackingStageResolver.TrackingStage stage = CustomerOrderTrackingStageResolver.resolve(
                "OUT_FOR_DELIVERY",
                "CAPTURED",
                "SHIPPED"
        );

        assertThat(stage.code()).isEqualTo("ON_THE_WAY");
        assertThat(stage.index()).isEqualTo(3);
    }

    @Test
    void resolvesDeliveredAsTerminalStage() {
        CustomerOrderTrackingStageResolver.TrackingStage stage = CustomerOrderTrackingStageResolver.resolve(
                "DELIVERED",
                "CAPTURED",
                "DELIVERED"
        );

        assertThat(stage.code()).isEqualTo("DELIVERED");
        assertThat(stage.index()).isEqualTo(4);
        assertThat(stage.terminal()).isTrue();
    }

    @Test
    void resolvesCancelledAsTerminalAndKeepsHighestReachedMainStageIndex() {
        CustomerOrderTrackingStageResolver.TrackingStage stage = CustomerOrderTrackingStageResolver.resolve(
                "CANCELLED",
                "CAPTURED",
                "CANCELLED"
        );

        assertThat(stage.code()).isEqualTo("CANCELLED");
        assertThat(stage.index()).isEqualTo(1);
        assertThat(stage.terminal()).isTrue();
    }

        @Test
        void prioritizesMostAdvancedReachableStageAcrossMixedSignals() {
        CustomerOrderTrackingStageResolver.TrackingStage delivered = CustomerOrderTrackingStageResolver.resolve(
            "PLACED",
            "CAPTURED",
            "DELIVERED"
        );
        CustomerOrderTrackingStageResolver.TrackingStage onTheWay = CustomerOrderTrackingStageResolver.resolve(
            "PACKING",
            "CAPTURED",
            "SHIPPED"
        );
        CustomerOrderTrackingStageResolver.TrackingStage packed = CustomerOrderTrackingStageResolver.resolve(
            "CONFIRMED",
            "CAPTURED",
            "ALLOCATED"
        );
        CustomerOrderTrackingStageResolver.TrackingStage payment = CustomerOrderTrackingStageResolver.resolve(
            "PAYMENT_PENDING",
            "AUTHORIZED",
            "PENDING"
        );

        assertThat(delivered.code()).isEqualTo("DELIVERED");
        assertThat(delivered.index()).isEqualTo(4);

        assertThat(onTheWay.code()).isEqualTo("ON_THE_WAY");
        assertThat(onTheWay.index()).isEqualTo(3);

        assertThat(packed.code()).isEqualTo("PACKED");
        assertThat(packed.index()).isEqualTo(2);

        assertThat(payment.code()).isEqualTo("PAYMENT");
        assertThat(payment.index()).isEqualTo(0);
        }
}
