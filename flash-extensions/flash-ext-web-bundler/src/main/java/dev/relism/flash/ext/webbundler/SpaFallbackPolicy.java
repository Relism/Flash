package dev.relism.flash.ext.webbundler;

import dev.relism.flash.models.Response;

final class SpaFallbackPolicy {
    private final byte[] html;

    SpaFallbackPolicy(AssetCatalog catalog) {
        this.html = catalog.index().raw();
    }

    void apply(Response response) {
        response.type("text/html");
        response.header("Cache-Control", "no-cache");
        response.body(html);
    }
}
