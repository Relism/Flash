package dev.relism.ext.webbundler;

public interface AssetsSource {
    AssetCatalog load(AssetLoadRequest request);
}
