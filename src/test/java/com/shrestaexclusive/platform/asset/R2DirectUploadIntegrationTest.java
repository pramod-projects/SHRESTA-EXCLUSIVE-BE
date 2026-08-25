package com.shrestaexclusive.platform.asset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

@Testcontainers
class R2DirectUploadIntegrationTest {

    private static final String ACCESS_KEY = "integration-access";
    private static final String SECRET_KEY = "integration-secret-12345678";
    private static final String BUCKET = "direct-upload-test";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000);

    private R2ObjectStorageClient objectStorage;

    @BeforeEach
    @SuppressWarnings("unused")
    void setUp() {
        AssetStorageProperties properties = new AssetStorageProperties();
        properties.setObjectEndpoint(endpoint().toString());
        properties.setObjectBucket(BUCKET);
        properties.setObjectRegion("us-east-1");
        properties.setObjectAccessKey(ACCESS_KEY);
        properties.setObjectSecretKey(SECRET_KEY);
        properties.setObjectPathStyle(true);
        properties.setBrowserCacheMaxAgeSeconds(2_592_000);
        properties.setUploadUrlTtl(Duration.ofMinutes(5));

        try (S3Client client = client()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
        objectStorage = new R2ObjectStorageClient(properties);
    }

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        objectStorage.close();
    }

    @Test
    void browserStylePutUploadsOneCanonicalObjectThenHeadVerifiesIt() throws Exception {
        String mediaId = UUID.randomUUID().toString();
        String objectKey = "products/product-one/images/" + mediaId + ".webp";
        byte[] canonicalBytes = "one-canonical-image".getBytes(StandardCharsets.UTF_8);
        R2ObjectStorageClient.PresignedUpload authorization = objectStorage.presignPut(
                mediaId, objectKey, "image/webp");

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(authorization.url()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(canonicalBytes));
        authorization.requiredHeaders().forEach(request::header);
        HttpResponse<Void> upload = HttpClient.newHttpClient().send(
                request.build(), HttpResponse.BodyHandlers.discarding());

        assertThat(upload.statusCode()).isBetween(200, 299);
        R2ObjectStorageClient.HeadedObject verified = objectStorage.head(objectKey);
        assertThat(verified.byteSize()).isEqualTo(canonicalBytes.length);
        assertThat(verified.contentType()).isEqualTo("image/webp");
        assertThat(verified.metadata()).containsEntry("media-id", mediaId);
        try (S3Client client = client()) {
            assertThat(client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build()).cacheControl())
                .isEqualTo("public,max-age=2592000");
        }

        objectStorage.delete(objectKey);
        assertThatThrownBy(() -> objectStorage.head(objectKey))
                .isInstanceOf(MediaObjectNotFoundException.class);
    }

    private S3Client client() {
        return S3Client.builder()
                .endpointOverride(endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private URI endpoint() {
        return URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    }
}