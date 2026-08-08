package com.shrestaexclusive.platform.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "shresta.sms")
public class CustomerSmsProperties {

    private boolean enabled = false;
    private String springEdgeEndpoint = "https://instantalerts.co/api/web/send";
    private String springEdgeApiKey = "";
    private String springEdgeSender = "SHRSTA";
    private String springEdgeRoute = "transactional";

    private String msg91Endpoint = "https://api.msg91.com/api/v2/sendsms";
    private String msg91AuthKey = "";
    private String msg91Sender = "SHRSTA";
    private String msg91Route = "4";
    private String msg91Country = "91";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSpringEdgeEndpoint() {
        return springEdgeEndpoint;
    }

    public void setSpringEdgeEndpoint(String springEdgeEndpoint) {
        this.springEdgeEndpoint = springEdgeEndpoint;
    }

    public String getSpringEdgeApiKey() {
        return springEdgeApiKey;
    }

    public void setSpringEdgeApiKey(String springEdgeApiKey) {
        this.springEdgeApiKey = springEdgeApiKey;
    }

    public String getSpringEdgeSender() {
        return springEdgeSender;
    }

    public void setSpringEdgeSender(String springEdgeSender) {
        this.springEdgeSender = springEdgeSender;
    }

    public String getSpringEdgeRoute() {
        return springEdgeRoute;
    }

    public void setSpringEdgeRoute(String springEdgeRoute) {
        this.springEdgeRoute = springEdgeRoute;
    }

    public String getMsg91Endpoint() {
        return msg91Endpoint;
    }

    public void setMsg91Endpoint(String msg91Endpoint) {
        this.msg91Endpoint = msg91Endpoint;
    }

    public String getMsg91AuthKey() {
        return msg91AuthKey;
    }

    public void setMsg91AuthKey(String msg91AuthKey) {
        this.msg91AuthKey = msg91AuthKey;
    }

    public String getMsg91Sender() {
        return msg91Sender;
    }

    public void setMsg91Sender(String msg91Sender) {
        this.msg91Sender = msg91Sender;
    }

    public String getMsg91Route() {
        return msg91Route;
    }

    public void setMsg91Route(String msg91Route) {
        this.msg91Route = msg91Route;
    }

    public String getMsg91Country() {
        return msg91Country;
    }

    public void setMsg91Country(String msg91Country) {
        this.msg91Country = msg91Country;
    }

    public boolean isDeliveryEnabled() {
        return enabled
                || StringUtils.hasText(springEdgeApiKey)
                || StringUtils.hasText(msg91AuthKey);
    }
}
