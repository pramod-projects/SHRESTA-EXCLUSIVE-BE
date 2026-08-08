package com.shrestaexclusive.platform.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.mail")
public class CustomerEmailProperties {

    private boolean enabled = false;
    private String fromAddress = "no-reply@shrestaexclusive.com";
    private String registrationOtpSubject = "Your SHRESTA registration OTP";
    private String invoiceSubjectPrefix = "SHRESTA Invoice";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getRegistrationOtpSubject() {
        return registrationOtpSubject;
    }

    public void setRegistrationOtpSubject(String registrationOtpSubject) {
        this.registrationOtpSubject = registrationOtpSubject;
    }

    public String getInvoiceSubjectPrefix() {
        return invoiceSubjectPrefix;
    }

    public void setInvoiceSubjectPrefix(String invoiceSubjectPrefix) {
        this.invoiceSubjectPrefix = invoiceSubjectPrefix;
    }
}
