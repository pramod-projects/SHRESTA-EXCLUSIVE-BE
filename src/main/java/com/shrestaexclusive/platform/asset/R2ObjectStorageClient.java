package com.shrestaexclusive.platform.asset;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
class R2ObjectStorageClient {

    private final AssetStorageProperties properties;
    private S3Client s3Client;
    private S3Presigner presigner;

    @Autowired
    R2ObjectStorageClient(AssetStorageProperties properties) {
        this.properties = properties;
    }

    private synchronized void initialize() {
        if (s3Client != null) {
            return;
        }
        validateConfiguration(properties);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.getObjectAccessKey(), properties.getObjectSecretKey()));
        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isObjectPathStyle())
                .build();
        URI endpoint = URI.create(properties.getObjectEndpoint());
        Region region = Region.of(properties.getObjectRegion());
        this.s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .region(region)
                .serviceConfiguration(serviceConfiguration)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .region(region)
                .serviceConfiguration(serviceConfiguration)
                .build();
    }

    PresignedUpload presignPut(String mediaId, String objectKey, String contentType) {
        try {
            initialize();
            Map<String, String> metadata = Map.of("media-id", mediaId);
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(properties.getObjectBucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .cacheControl(properties.getObjectCacheControl())
                    .metadata(metadata)
                    .build();
            PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(properties.getUploadUrlTtl())
                    .putObjectRequest(put)
                    .build());
            Map<String, String> headers = new LinkedHashMap<>();
            signed.signedHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("host") && !values.isEmpty()) {
                    headers.put(name, values.getFirst());
                }
            });
            return new PresignedUpload(signed.url().toString(), Instant.now().plus(properties.getUploadUrlTtl()), headers);
        } catch (SdkException exception) {
            throw new MediaStorageException("Media upload authorization is temporarily unavailable", exception);
        }
    }

    HeadedObject head(String objectKey) {
        try {
            initialize();
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getObjectBucket())
                    .key(objectKey)
                    .build());
            return new HeadedObject(response.contentLength(), response.contentType(), response.eTag(), response.metadata());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new MediaObjectNotFoundException(exception);
            }
            throw new MediaStorageException("Media verification is temporarily unavailable", exception);
        } catch (SdkException exception) {
            throw new MediaStorageException("Media verification is temporarily unavailable", exception);
        }
    }

    void delete(String objectKey) {
        try {
            initialize();
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getObjectBucket())
                    .key(objectKey)
                    .build());
        } catch (SdkException exception) {
            throw new MediaStorageException("Media deletion is temporarily unavailable", exception);
        }
    }

    @PreDestroy
    public void close() {
        if (presigner != null) {
            presigner.close();
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    private static void validateConfiguration(AssetStorageProperties properties) {
        if (!StringUtils.hasText(properties.getObjectEndpoint())
                || !StringUtils.hasText(properties.getObjectBucket())
                || !StringUtils.hasText(properties.getObjectRegion())
                || !StringUtils.hasText(properties.getObjectAccessKey())
                || !StringUtils.hasText(properties.getObjectSecretKey())) {
            throw new IllegalStateException("R2 object storage configuration is incomplete");
        }
    }

    record PresignedUpload(String url, Instant expiresAt, Map<String, String> requiredHeaders) {
    }

    record HeadedObject(long byteSize, String contentType, String etag, Map<String, String> metadata) {
    }
}