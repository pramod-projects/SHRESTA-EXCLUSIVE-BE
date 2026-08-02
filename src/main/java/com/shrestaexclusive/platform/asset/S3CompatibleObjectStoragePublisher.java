package com.shrestaexclusive.platform.asset;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class S3CompatibleObjectStoragePublisher {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final AssetStorageProperties properties;
    private final HttpClient httpClient;
    private final Clock clock;

    @Autowired
    public S3CompatibleObjectStoragePublisher(AssetStorageProperties properties) {
        this(properties, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    S3CompatibleObjectStoragePublisher(AssetStorageProperties properties, HttpClient httpClient, Clock clock) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public void publish(String storageKey, Path source, String contentType) throws IOException {
        if (!properties.isObjectUploadEnabled()) {
            return;
        }
        if (!StringUtils.hasText(storageKey)) {
            throw new IllegalArgumentException("storageKey is required");
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("Object source file not found: " + source);
        }
        validateConfig();

        byte[] payload = Files.readAllBytes(source);
        SignedPut signedPut = signedPut(storageKey, contentType, payload);
        HttpRequest request = HttpRequest.newBuilder(signedPut.uri())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .header("Authorization", signedPut.authorization())
                .header("Cache-Control", properties.getObjectCacheControl())
                .header("Content-Type", normalizedContentType(contentType))
                .header("x-amz-content-sha256", signedPut.payloadHash())
                .header("x-amz-date", signedPut.amzDate())
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Object storage upload failed for %s with HTTP %d".formatted(storageKey, response.statusCode()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Object storage upload interrupted for " + storageKey, exception);
        }
    }

    private SignedPut signedPut(String storageKey, String contentType, byte[] payload) {
        URI endpoint = URI.create(properties.getObjectEndpoint());
        String bucket = properties.getObjectBucket().trim();
        String key = stripLeadingSlash(storageKey.trim());
        String canonicalUri = canonicalUri(bucket, key);
        String host = host(endpoint, bucket);
        String target = endpoint.getScheme() + "://" + host + canonicalUri;
        String payloadHash = sha256Hex(payload);
        Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String normalizedContentType = normalizedContentType(contentType);
        String signedHeaders = "cache-control;content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = ""
                + "cache-control:" + properties.getObjectCacheControl() + "\n"
                + "content-type:" + normalizedContentType + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String canonicalRequest = "PUT\n"
                + canonicalUri + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = "%s/%s/s3/aws4_request".formatted(dateStamp, properties.getObjectRegion());
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = signature(dateStamp, stringToSign);
        String authorization = "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s"
                .formatted(properties.getObjectAccessKey(), credentialScope, signedHeaders, signature);
        return new SignedPut(URI.create(target), authorization, payloadHash, amzDate);
    }

    private String canonicalUri(String bucket, String key) {
        if (properties.isObjectPathStyle()) {
            return "/" + percentEncode(bucket) + "/" + percentEncodePath(key);
        }
        return "/" + percentEncodePath(key);
    }

    private String host(URI endpoint, String bucket) {
        String endpointHost = endpoint.getHost();
        if (!StringUtils.hasText(endpointHost)) {
            throw new IllegalArgumentException("Object endpoint host is required");
        }
        String host = properties.isObjectPathStyle() ? endpointHost : bucket + "." + endpointHost;
        int port = endpoint.getPort();
        if (port >= 0) {
            host += ":" + port;
        }
        return host;
    }

    private void validateConfig() {
        if (!StringUtils.hasText(properties.getObjectEndpoint())
                || !StringUtils.hasText(properties.getObjectBucket())
                || !StringUtils.hasText(properties.getObjectRegion())
                || !StringUtils.hasText(properties.getObjectAccessKey())
                || !StringUtils.hasText(properties.getObjectSecretKey())) {
            throw new IllegalStateException("Object storage publishing is enabled but endpoint, bucket, region, access key, or secret key is missing");
        }
    }

    private String normalizedContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType.trim().toLowerCase(Locale.ROOT) : "application/octet-stream";
    }

    private String signature(String dateStamp, String stringToSign) {
        byte[] dateKey = hmac(("AWS4" + properties.getObjectSecretKey()).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] dateRegionKey = hmac(dateKey, properties.getObjectRegion());
        byte[] dateRegionServiceKey = hmac(dateRegionKey, "s3");
        byte[] signingKey = hmac(dateRegionServiceKey, "aws4_request");
        return HexFormat.of().formatHex(hmac(signingKey, stringToSign));
    }

    private byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HmacSHA256 signing is unavailable", exception);
        }
    }

    private String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String percentEncodePath(String value) {
        String[] segments = value.split("/", -1);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                builder.append('/');
            }
            builder.append(percentEncode(segments[index]));
        }
        return builder.toString();
    }

    private String percentEncode(String value) {
        StringBuilder builder = new StringBuilder();
        for (byte rawByte : value.getBytes(StandardCharsets.UTF_8)) {
            int candidate = rawByte & 0xff;
            if (isUnreserved(candidate)) {
                builder.append((char) candidate);
            } else {
                builder.append('%');
                builder.append(String.format("%02X", candidate));
            }
        }
        return builder.toString();
    }

    private boolean isUnreserved(int candidate) {
        return candidate >= 'A' && candidate <= 'Z'
                || candidate >= 'a' && candidate <= 'z'
                || candidate >= '0' && candidate <= '9'
                || candidate == '-'
                || candidate == '_'
                || candidate == '.'
                || candidate == '~';
    }

    private String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    /** Sends a signed AWS S3 DELETE request for the given storage key. 404 is silently ignored. */
    public void delete(String storageKey) throws IOException {
        if (!properties.isObjectUploadEnabled()) {
            return;
        }
        if (!StringUtils.hasText(storageKey)) {
            return;
        }
        validateConfig();

        URI endpoint = URI.create(properties.getObjectEndpoint());
        String bucket = properties.getObjectBucket().trim();
        String key = stripLeadingSlash(storageKey.trim());
        String canonicalUri = canonicalUri(bucket, key);
        String host = host(endpoint, bucket);
        String target = endpoint.getScheme() + "://" + host + canonicalUri;
        String emptyHash = sha256Hex(new byte[0]);
        Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + emptyHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String canonicalRequest = "DELETE\n"
                + canonicalUri + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + emptyHash;
        String credentialScope = "%s/%s/s3/aws4_request".formatted(dateStamp, properties.getObjectRegion());
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = signature(dateStamp, stringToSign);
        String authorization = "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s"
                .formatted(properties.getObjectAccessKey(), credentialScope, signedHeaders, signature);

        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .DELETE()
                .header("Authorization", authorization)
                .header("x-amz-content-sha256", emptyHash)
                .header("x-amz-date", amzDate)
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300 && response.statusCode() != 404) {
                throw new IOException("Object storage delete failed for %s with HTTP %d".formatted(storageKey, response.statusCode()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Object storage delete interrupted for " + storageKey, exception);
        }
    }

    private record SignedPut(URI uri, String authorization, String payloadHash, String amzDate) {
    }
}
