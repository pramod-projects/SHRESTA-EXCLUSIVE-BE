package com.shrestaexclusive.platform.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
class LocalSipsAssetVariantProcessor implements AssetVariantProcessor {

    private static final List<VariantSpec> VARIANTS = List.of(
            new VariantSpec("thumbnail", 160),
            new VariantSpec("small", 320),
            new VariantSpec("medium", 640),
            new VariantSpec("large", 1024)
    );

    private final AssetStorageService storageService;

    LocalSipsAssetVariantProcessor(AssetStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public ProcessedAsset process(StoredAsset original) throws IOException, InterruptedException {
        List<GeneratedVariant> variants = new ArrayList<>();
        String originalFormat = original.contentType().equals("image/png") ? "png" : "jpg";
        variants.add(new GeneratedVariant(
                "original",
                originalFormat,
                original.widthPx(),
                original.heightPx(),
                original.byteSize(),
                original.storageKey(),
                original.assetUrl(),
                original.contentType()
        ));

        for (VariantSpec spec : VARIANTS) {
            String storageKey = "assets/%s/v%d/variants/%s.jpg".formatted(original.assetKey(), original.version(), spec.widthPx());
            Path output = storageService.resolve(storageKey);
            Files.createDirectories(output.getParent());
            runSips(original.storageKey(), output, spec.widthPx());

            var image = ImageIO.read(output.toFile());
            variants.add(new GeneratedVariant(
                    spec.variantKey(),
                    "jpg",
                    image.getWidth(),
                    image.getHeight(),
                    Files.size(output),
                    storageKey,
                    storageService.publicPath(storageKey),
                    "image/jpeg"
            ));
            storageService.publishObject(storageKey, "image/jpeg");

            generateWebpIfSupported(original, output, spec, variants);
            generateAvifIfSupported(original, output, spec, variants);
        }

        String lqip = lqip(original);
        return new ProcessedAsset(variants, lqip);
    }

    private void runSips(String inputStorageKey, Path output, int widthPx) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "/usr/bin/sips",
                "-s", "format", "jpeg",
                "-Z", String.valueOf(widthPx),
                storageService.resolve(inputStorageKey).toString(),
                "--out",
                output.toString()
        ).redirectErrorStream(true).start();

        if (process.waitFor() != 0) {
            throw new IOException("sips image variant generation failed");
        }
    }

    private void generateWebpIfSupported(StoredAsset original, Path jpegInput, VariantSpec spec, List<GeneratedVariant> variants)
            throws IOException, InterruptedException {
        if (!commandExists("cwebp")) {
            return;
        }

        String storageKey = "assets/%s/v%d/variants/%s.webp".formatted(original.assetKey(), original.version(), spec.widthPx());
        Path output = storageService.resolve(storageKey);
        Process process = new ProcessBuilder("cwebp", "-quiet", "-q", "82", jpegInput.toString(), "-o", output.toString()).start();
        if (process.waitFor() == 0 && Files.exists(output)) {
            var image = ImageIO.read(jpegInput.toFile());
            storageService.publishObject(storageKey, "image/webp");
            variants.add(new GeneratedVariant(spec.variantKey(), "webp", image.getWidth(), image.getHeight(), Files.size(output), storageKey, storageService.publicPath(storageKey), "image/webp"));
        }
    }

    private void generateAvifIfSupported(StoredAsset original, Path jpegInput, VariantSpec spec, List<GeneratedVariant> variants)
            throws IOException, InterruptedException {
        if (!commandExists("avifenc")) {
            return;
        }

        String storageKey = "assets/%s/v%d/variants/%s.avif".formatted(original.assetKey(), original.version(), spec.widthPx());
        Path output = storageService.resolve(storageKey);
        Process process = new ProcessBuilder("avifenc", "--min", "20", "--max", "32", jpegInput.toString(), output.toString()).start();
        if (process.waitFor() == 0 && Files.exists(output)) {
            var image = ImageIO.read(jpegInput.toFile());
            storageService.publishObject(storageKey, "image/avif");
            variants.add(new GeneratedVariant(spec.variantKey(), "avif", image.getWidth(), image.getHeight(), Files.size(output), storageKey, storageService.publicPath(storageKey), "image/avif"));
        }
    }

    private String lqip(StoredAsset original) throws IOException, InterruptedException {
        String storageKey = "assets/%s/v%d/variants/lqip.jpg".formatted(original.assetKey(), original.version());
        Path output = storageService.resolve(storageKey);
        Files.createDirectories(output.getParent());
        runSips(original.storageKey(), output, 24);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(output));
    }

    private boolean commandExists(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/sh", "-lc", "command -v " + command).start();
        return process.waitFor() == 0;
    }

    private record VariantSpec(String variantKey, int widthPx) {
    }
}
