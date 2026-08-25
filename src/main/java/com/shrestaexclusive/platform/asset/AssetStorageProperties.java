package com.shrestaexclusive.platform.asset;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.assets")
public class AssetStorageProperties {

    private String objectAccountId = "";

    private String objectEndpoint = "";

    private String objectBucket = "";

    private String objectRegion = "";

    private String objectAccessKey = "";

    private String objectSecretKey = "";

    private boolean objectPathStyle = true;

    private long browserCacheMaxAgeSeconds = 3600;

    private Duration uploadUrlTtl = Duration.ofMinutes(10);

    private int canonicalMaxWidth = 4000;

    private int canonicalMaxHeight = 5333;

    private long canonicalMaxFileSize = 15_000_000;

    private long videoMaxFileSize = 100_000_000;

    public String getObjectAccountId() {
        return objectAccountId;
    }

    public void setObjectAccountId(String objectAccountId) {
        this.objectAccountId = objectAccountId;
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
        return "public,max-age=" + browserCacheMaxAgeSeconds;
    }

    public long getBrowserCacheMaxAgeSeconds() {
        return browserCacheMaxAgeSeconds;
    }

    public void setBrowserCacheMaxAgeSeconds(long browserCacheMaxAgeSeconds) {
        this.browserCacheMaxAgeSeconds = browserCacheMaxAgeSeconds;
    }

    public Duration getUploadUrlTtl() {
        return uploadUrlTtl;
    }

    public void setUploadUrlTtl(Duration uploadUrlTtl) {
        this.uploadUrlTtl = uploadUrlTtl;
    }

    public int getCanonicalMaxWidth() {
        return canonicalMaxWidth;
    }

    public void setCanonicalMaxWidth(int canonicalMaxWidth) {
        this.canonicalMaxWidth = canonicalMaxWidth;
    }

    public int getCanonicalMaxHeight() {
        return canonicalMaxHeight;
    }

    public void setCanonicalMaxHeight(int canonicalMaxHeight) {
        this.canonicalMaxHeight = canonicalMaxHeight;
    }

    public long getCanonicalMaxFileSize() {
        return canonicalMaxFileSize;
    }

    public void setCanonicalMaxFileSize(long canonicalMaxFileSize) {
        this.canonicalMaxFileSize = canonicalMaxFileSize;
    }

    public long getVideoMaxFileSize() {
        return videoMaxFileSize;
    }

    public void setVideoMaxFileSize(long videoMaxFileSize) {
        this.videoMaxFileSize = videoMaxFileSize;
    }
}
