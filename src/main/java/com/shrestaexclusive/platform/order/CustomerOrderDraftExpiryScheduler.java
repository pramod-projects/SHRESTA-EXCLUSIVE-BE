package com.shrestaexclusive.platform.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustomerOrderDraftExpiryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerOrderDraftExpiryScheduler.class);

    private final CustomerOrderService customerOrderService;

    public CustomerOrderDraftExpiryScheduler(CustomerOrderService customerOrderService) {
        this.customerOrderService = customerOrderService;
    }

    @Scheduled(
            fixedDelayString = "${shresta.orders.draft-expiry.fixed-delay-ms:30000}",
            initialDelayString = "${shresta.orders.draft-expiry.initial-delay-ms:15000}"
    )
    public void expireStaleDraftReservations() {
        int expiredDraftCount = customerOrderService.expireGloballyExpiredDrafts();
        if (expiredDraftCount > 0) {
            LOG.info("order-draft-expiry released {} stale checkout draft reservations", expiredDraftCount);
        }
    }
}
