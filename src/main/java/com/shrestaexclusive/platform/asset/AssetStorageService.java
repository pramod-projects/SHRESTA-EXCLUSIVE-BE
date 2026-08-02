package com.shrestaexclusive.platform.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetStorageService {

    private static final Logger log = LoggerFactory.getLogger(AssetStorageService.class);

    private final AssetStorageProperties properties;
    private final S3CompatibleObjectStoragePublisher objectStoragePublisher;

    public AssetStorageService(AssetStorageProperties properties, S3CompatibleObjectStoragePublisher objectStoragePublisher) {
        this.properties = properties;
        this.objectStoragePublisher = objectStoragePublisher;
    }

    public StoredAsset storeOriginal(MultipartFile file) throws IOException {
        String assetKey = slug(file == null ? null : file.getOriginalFilename()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        return storeVersionedOriginal(assetKey, 1, file);
    }

    public StoredAsset storeReplacement(String assetKey, int version, MultipartFile file) throws IOException {
        if (!StringUtils.hasText(assetKey)) {
            throw new IllegalArgumentException("assetKey is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        return storeVersionedOriginal(assetKey.trim(), version, file);
    }

    private StoredAsset storeVersionedOriginal(String assetKey, int version, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Asset file is required");
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new IllegalArgumentException("Only image assets are supported");
        }

        String extension = extension(file.getOriginalFilename(), file.getContentType());
        String storageKey = "assets/%s/v%d/original/original.%s".formatted(assetKey, version, extension);
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());

        MessageDigest digest = sha256Digest();
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(input, target);
        }

        var image = ImageIO.read(target.toFile());
        if (image == null) {
            throw new IllegalArgumentException("Uploaded file is not a readable image");
        }
        publishObject(storageKey, file.getContentType());

        return new StoredAsset(
                assetKey,
                StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : assetKey + "." + extension,
                storageKey,
                storageKey,
                properties.getStorageProvider(),
                properties.getDeliveryMode(),
                version,
                file.getContentType(),
                Files.size(target),
                HexFormat.of().formatHex(digest.digest()),
                image.getWidth(),
                image.getHeight()
        );
    }

    public Path resolve(String storageKey) {
        return Path.of(properties.getLocalStorageRoot()).toAbsolutePath().normalize().resolve(storageKey).normalize();
    }

    public String publicPath(String storageKey) {
        return storageKey;
    }

    public void publishObject(String storageKey, String contentType) throws IOException {
        objectStoragePublisher.publish(storageKey, resolve(storageKey), contentType);
    }

    /** Deletes all given storage keys from S3 and the local filesystem. Errors are logged but not rethrown. */
    public void deleteObjects(List<String> storageKeys) {
        for (String storageKey : storageKeys) {
            if (!StringUtils.hasText(storageKey)) {
                continue;
            }
            try {
                objectStoragePublisher.delete(storageKey);
            } catch (IOException exception) {
                log.warn("Failed to delete object from S3 for key {}: {}", storageKey, exception.getMessage());
            }
            try {
                Files.deleteIfExists(resolve(storageKey));
            } catch (IOException exception) {
                log.warn("Failed to delete local file for key {}: {}", storageKey, exception.getMessage());
            }
        }
    }

    /**
     * Deletes the local filesystem copy of an uploaded asset and all its variants after they
     * have been successfully published to S3/MinIO. No-op when object upload is disabled
     * because in that mode the local filesystem IS the storage.
     */
    public void deleteLocalFiles(String assetKey, int version) {
        if (!properties.isObjectUploadEnabled()) {
            return;
        }
        Path versionDir = Path.of(properties.getLocalStorageRoot())
                .toAbsolutePath()
                .normalize()
                .resolve("assets/" + assetKey + "/v" + version);
        if (!Files.exists(versionDir)) {
            return;
        }
        try (var stream = Files.walk(versionDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("Failed to delete local asset file {}: {}", path, exception.getMessage());
                }
            });
        } catch (IOException exception) {
            log.warn("Failed to clean up local asset directory {}: {}", versionDir, exception.getMessage());
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String extension(String filename, String contentType) {
        if (StringUtils.hasText(filename) && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            default -> "jpg";
        };
    }

    private String slug(String filename) {
        String base = StringUtils.hasText(filename) ? filename : "asset";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String normalized = Normalizer.normalize(base, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return StringUtils.hasText(normalized) ? normalized : "asset";
    }
}
