package dev.relism.flash.ext.webbundler;

import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

final class StaticAssetServingPolicy {
    private final AssetCatalog catalog;

    StaticAssetServingPolicy(AssetCatalog catalog) {
        this.catalog = catalog;
    }

    boolean serve(Request req, Response res) {
        AssetEntry entry = catalog.find(req.path());
        if (entry == null) return false;
        String inm = req.header("If-None-Match");
        if (entry.etag().equals(inm)) {
            res.status(HttpStatus.NOT_MODIFIED);
            return true;
        }
        res.type(entry.mimeType());
        if (entry.immutable()) {
            res.header("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            res.header("Cache-Control", "no-cache");
        }
        res.header("ETag", entry.etag());
        res.header("Vary", "Accept-Encoding");
        if (accepts(req, "br") && entry.br() != null) {
            res.header("Content-Encoding", "br");
            res.body(entry.br());
        } else if (accepts(req, "gzip") && entry.gz() != null) {
            res.header("Content-Encoding", "gzip");
            res.body(entry.gz());
        } else {
            res.body(entry.raw());
        }
        return true;
    }

    private static boolean accepts(Request req, String encoding) {
        String value = req.header("Accept-Encoding");
        return value != null && value.contains(encoding);
    }
}
