package com.shrestaexclusive.platform.kv;

import java.time.Duration;

public class KvTableProperties {

    private boolean enabled = true;
    private Duration ttl = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
