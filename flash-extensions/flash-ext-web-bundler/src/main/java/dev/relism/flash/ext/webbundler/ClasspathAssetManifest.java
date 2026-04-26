package dev.relism.flash.ext.webbundler;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

record ClasspathAssetManifest(@JsonProperty("assets") List<Entry> assets) {
    record Entry(
            @JsonProperty("routePath") String routePath,
            @JsonProperty("resourcePath") String resourcePath,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("etag") String etag,
            @JsonProperty("immutable") boolean immutable
    ) {
    }
}
