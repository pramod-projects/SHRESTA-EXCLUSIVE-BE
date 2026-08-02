package com.shrestaexclusive.platform.asset;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.assets")
public class AssetStorageProperties {

    private String localStorageRoot = "var/shresta-assets";

    private String storageProvider = "s3-compatible";

    private String deliveryMode = "s3-compatible";

    private boolean objectUploadEnabled = false;

    private String objectEndpoint = "";

    private String objectBucket = "";

    private String objectRegion = "";

    private String objectAccessKey = "";

    private String objectSecretKey = "";

    private boolean objectPathStyle = true;

    private String objectCacheControl = "public,max-age=31536000,immutable";

    public String getLocalStorageRoot() {
        return localStorageRoot;
    }

    public void setLocalStorageRoot(String localStorageRoot) {
        this.localStorageRoot = localStorageRoot;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public void setStorageProvider(String storageProvider) {
        this.storageProvider = storageProvider;
    }

    public String getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(String deliveryMode) {
        this.deliveryMode = deliveryMode;
    }

    public boolean isObjectUploadEnabled() {
        return objectUploadEnabled;
    }

    public void setObjectUploadEnabled(boolean objectUploadEnabled) {
        this.objectUploadEnabled = objectUploadEnabled;
    }

    public String getObjectEndpoint() {
        return objectEndpoint;
    }

    public void setObjectEndpoint(String objectEndpoint) {
        this.objectEndpoint = objectEndpoint;
    }

    public String getObjectBucket() {
        return objectBucket;
    }

    public void setObjectBucket(String objectBucket) {
        this.objectBucket = objectBucket;
    }

    public String getObjectRegion() {
        return objectRegion;
    }

    public void setObjectRegion(String objectRegion) {
        this.objectRegion = objectRegion;
    }

    public String getObjectAccessKey() {
        return objectAccessKey;
    }

    public void setObjectAccessKey(String objectAccessKey) {
        this.objectAccessKey = objectAccessKey;
    }

    public String getObjectSecretKey() {
        return objectSecretKey;
    }

    public void setObjectSecretKey(String objectSecretKey) {
        this.objectSecretKey = objectSecretKey;
    }

    public boolean isObjectPathStyle() {
        return objectPathStyle;
    }

    public void setObjectPathStyle(boolean objectPathStyle) {
        this.objectPathStyle = objectPathStyle;
    }

    public String getObjectCacheControl() {
        return objectCacheControl;
    }

    public void setObjectCacheControl(String objectCacheControl) {
        this.objectCacheControl = objectCacheControl;
    }
}
