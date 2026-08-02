package com.shrestaexclusive.platform.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3CompatibleObjectStoragePublisherTest {

    @TempDir
    private Path tempDir;

    @Test
    void skipsPublishingWhenObjectUploadIsDisabled() throws Exception {
        AssetStorageProperties properties = new AssetStorageProperties();
        properties.setObjectUploadEnabled(false);
        S3CompatibleObjectStoragePublisher publisher = new S3CompatibleObjectStoragePublisher(
                properties,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );

        publisher.publish("assets/example/v1/original/original.jpg", tempDir.resolve("missing.jpg"), "image/jpeg");
    }

    @Test
    void publishesPathStyleS3CompatiblePut() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> cacheControl = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cacheControl.set(exchange.getRequestHeaders().getFirst("Cache-Control"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            Path source = tempDir.resolve("asset.jpg");
            Files.write(source, new byte[]{1, 2, 3, 4});
            AssetStorageProperties properties = new AssetStorageProperties();
            properties.setObjectUploadEnabled(true);
            properties.setObjectEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setObjectBucket("shresta-local-assets");
            properties.setObjectRegion("us-east-1");
            properties.setObjectAccessKey("local");
            properties.setObjectSecretKey("secret");

            S3CompatibleObjectStoragePublisher publisher = new S3CompatibleObjectStoragePublisher(
                    properties,
                    HttpClient.newHttpClient(),
                    Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
            );

            publisher.publish("assets/example/v1/original/original.jpg", source, "image/jpeg");

            assertThat(requestPath.get()).isEqualTo("/shresta-local-assets/assets/example/v1/original/original.jpg");
            assertThat(contentType.get()).isEqualTo("image/jpeg");
            assertThat(cacheControl.get()).isEqualTo("public,max-age=31536000,immutable");
            assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 Credential=local/20260706/us-east-1/s3/aws4_request");
        } finally {
            server.stop(0);
        }
    }
}
