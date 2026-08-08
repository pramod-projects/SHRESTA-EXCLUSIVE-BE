package com.shrestaexclusive.platform.notification;

import java.util.UUID;
import com.shrestaexclusive.platform.sms.CustomerSmsDeliveryService;
import org.springframework.stereotype.Service;

@Service
public class CustomerNotificationService {

    private final CustomerSmsDeliveryService smsDeliveryService;
    private final CustomerEmailDeliveryService emailDeliveryService;

    public CustomerNotificationService(CustomerSmsDeliveryService smsDeliveryService, CustomerEmailDeliveryService emailDeliveryService) {
        this.smsDeliveryService = smsDeliveryService;
        this.emailDeliveryService = emailDeliveryService;
    }

    public void sendRegistrationOtp(UUID customerId, String email, String mobile, String otp) {
        emailDeliveryService.sendRegistrationOtp(customerId, email, otp);
        smsDeliveryService.sendRegistrationOtp(customerId, mobile, otp);
    }

    public void sendGeneralNotification(UUID customerId, String mobile, String messageBody) {
        smsDeliveryService.sendCustomerSms(customerId, mobile, "GENERAL_NOTIFICATION", messageBody);
    }

    public void sendInvoiceNotification(UUID customerId, String email, String orderNumber, String invoiceBody) {
        emailDeliveryService.sendInvoiceNotification(customerId, email, orderNumber, invoiceBody);
    }
}
