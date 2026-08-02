package com.shrestaexclusive.platform.kv;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shresta.kv")
public class KvCacheProperties {

    private boolean enabled = true;
    private String keyPrefix = "shresta:local";
    private Map<String, KvTableProperties> tables = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Map<String, KvTableProperties> getTables() {
        return tables;
    }

    public void setTables(Map<String, KvTableProperties> tables) {
        this.tables = tables;
    }
}
