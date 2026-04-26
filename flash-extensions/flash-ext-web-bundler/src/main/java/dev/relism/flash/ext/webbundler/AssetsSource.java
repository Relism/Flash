package dev.relism.flash.ext.webbundler;

public interface AssetsSource {
    AssetCatalog load(AssetLoadRequest request);
}
