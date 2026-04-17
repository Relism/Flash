package dev.relism.ext.webbundler;

import java.util.Map;

public final class AssetCatalog {
    private final Map<String, AssetEntry> byRoutePath;
    private final AssetEntry index;

    AssetCatalog(Map<String, AssetEntry> byRoutePath, AssetEntry index) {
        this.byRoutePath = Map.copyOf(byRoutePath);
        this.index = index;
    }

    AssetEntry find(String routePath) {
        return byRoutePath.get(routePath);
    }

    AssetEntry index() {
        return index;
    }

    public AssetEntry asset(String routePath) {
        return find(routePath);
    }

    public AssetEntry indexAsset() {
        return index;
    }

    public int size() {
        return byRoutePath.size();
    }
}
