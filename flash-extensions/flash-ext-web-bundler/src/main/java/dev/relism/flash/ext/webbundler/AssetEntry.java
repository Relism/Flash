package dev.relism.flash.ext.webbundler;

public record AssetEntry(
        byte[] raw,
        byte[] br,
        byte[] gz,
        String etag,
        String mimeType,
        boolean immutable
) {
}
