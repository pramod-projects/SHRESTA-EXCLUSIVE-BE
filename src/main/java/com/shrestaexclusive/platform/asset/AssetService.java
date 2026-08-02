package com.shrestaexclusive.platform.asset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetService {

    public static final List<String> MEDIA_TABLES = List.of("media_assets", "media_asset_variants");
    private static final TypeReference<AssetSearchResponse> ASSET_SEARCH_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<AssetResponse> ASSET_RESPONSE = new TypeReference<>() {
    };

    private final AssetRepository repository;
    private final AssetStorageService storageService;
    private final AssetVariantProcessor variantProcessor;
    private final KvReadThroughCache kvCache;
    private final StorefrontHomeService storefrontHomeService;

    public AssetService(
            AssetRepository repository,
            AssetStorageService storageService,
            AssetVariantProcessor variantProcessor,
            KvReadThroughCache kvCache,
            StorefrontHomeService storefrontHomeService
    ) {
        this.repository = repository;
        this.storageService = storageService;
        this.variantProcessor = variantProcessor;
        this.kvCache = kvCache;
        this.storefrontHomeService = storefrontHomeService;
    }

    @Transactional(readOnly = true)
    public AssetSearchResponse search(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status, int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        String cacheKey = String.join(":",
                normalized(query),
                normalized(categoryFamilyKey),
                normalized(categoryProductTypeKey),
                normalized(productSku),
                normalized(status),
                Integer.toString(normalizedPage),
                Integer.toString(normalizedSize)
        );
        return kvCache.getOrLoad(
                "asset-search",
                cacheKey,
                MEDIA_TABLES,
                ASSET_SEARCH_RESPONSE,
                () -> repository.search(query, categoryFamilyKey, categoryProductTypeKey, productSku, status, normalizedPage, normalizedSize)
        );
    }

    @Transactional(readOnly = true)
    public AssetResponse get(String assetKey) {
        return kvCache.getOrLoad(
                "asset-detail",
                assetKey,
                MEDIA_TABLES,
                ASSET_RESPONSE,
                () -> repository.findByAssetKey(assetKey).orElseThrow(() -> new AssetNotFoundException(assetKey))
        );
    }

    @Transactional
    public List<AssetResponse> upload(List<MultipartFile> files, AssetUploadRequest request) {
        List<AssetResponse> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            uploaded.add(uploadOne(file, request));
        }
        publishMediaTablesAfterCommit();
        return uploaded;
    }

    @Transactional
    public AssetResponse replaceImage(String assetKey, MultipartFile file) {
        AssetReplacementTarget target = repository.replacementTargetForUpdate(assetKey);
        StoredAsset storedAsset = null;
        try {
            storedAsset = storageService.storeReplacement(assetKey, target.nextVersion(), file);
            repository.replaceOriginal(target.assetId(), storedAsset);
            AssetVariantProcessor.ProcessedAsset processedAsset = variantProcessor.process(storedAsset);
            repository.replaceVariants(target.assetId(), processedAsset.variants(), processedAsset.lqipDataUrl());
            repository.markReady(target.assetId());
            storageService.deleteLocalFiles(storedAsset.assetKey(), storedAsset.version());
            AssetResponse response = repository.findByAssetKey(assetKey).orElseThrow(() -> new AssetNotFoundException(assetKey));
            publishMediaTablesAfterCommit();
            return response;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            repository.markFailed(target.assetId(), exception.getMessage());
            throw new IllegalStateException("Asset replacement failed for " + assetKey, exception);
        }
    }

    @Transactional
    public AssetResponse updateMetadata(String assetKey, AssetMetadataUpdateRequest request) {
        repository.updateMetadata(assetKey, request);
        AssetResponse response = repository.findByAssetKey(assetKey).orElseThrow(() -> new AssetNotFoundException(assetKey));
        publishMediaTablesAfterCommit();
        return response;
    }

    @Transactional
    public void archive(String assetKey) {
        List<String> storageKeys = repository.findStorageKeysByAssetKey(assetKey);
        repository.archive(assetKey);
        publishMediaTablesAfterCommit();
        deleteObjectsAfterCommit(storageKeys);
    }

    @Transactional
    public void deletePermanently(String assetKey) {
        List<String> storageKeys = repository.findStorageKeysByAssetKey(assetKey);
        repository.deletePermanently(assetKey);
        publishMediaTablesAfterCommit();
        deleteObjectsAfterCommit(storageKeys);
    }

    @Transactional
    public AssetSearchResponse bulkAssignCategory(BulkCategoryAssignmentRequest request) {
        repository.bulkAssignCategory(request.assetKeys(), request.categoryFamilyKey(), request.categoryProductTypeKey());
        AssetSearchResponse response = repository.search(null, request.categoryFamilyKey(), request.categoryProductTypeKey(), null, null, 0, 50);
        publishMediaTablesAfterCommit();
        return response;
    }

    private AssetResponse uploadOne(MultipartFile file, AssetUploadRequest request) {
        UUID assetId = null;
        StoredAsset storedAsset = null;
        try {
            storedAsset = storageService.storeOriginal(file);
            assetId = repository.insertUploadedAsset(storedAsset, request);
            AssetVariantProcessor.ProcessedAsset processedAsset = variantProcessor.process(storedAsset);
            repository.replaceVariants(assetId, processedAsset.variants(), processedAsset.lqipDataUrl());
            repository.markReady(assetId);
            storageService.deleteLocalFiles(storedAsset.assetKey(), storedAsset.version());
            return get(storedAsset.assetKey());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (assetId != null) {
                repository.markFailed(assetId, exception.getMessage());
            }
            throw new IllegalStateException("Asset upload failed for " + (storedAsset == null ? file.getOriginalFilename() : storedAsset.assetKey()), exception);
        }
    }

    private void publishMediaTablesAfterCommit() {
        Runnable publisher = () -> {
            kvCache.invalidateTables(MEDIA_TABLES);
            storefrontHomeService.refreshHomeKv();
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.run();
                }
            });
        } else {
            publisher.run();
        }
    }

    private void deleteObjectsAfterCommit(List<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return;
        }
        Runnable deleter = () -> storageService.deleteObjects(storageKeys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleter.run();
                }
            });
        } else {
            deleter.run();
        }
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "_" : value.trim();
    }
}
