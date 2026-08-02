package com.shrestaexclusive.platform.asset;

public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(String assetKey) {
        super("Asset not found: " + assetKey);
    }
}
