package dev.relism.ext.view.core;

import dev.relism.http.ContentType;

/**
 * Render result returned by engine runtimes.
 *
 * @param body rendered response body
 * @param contentType optional explicit response content type; if null caller keeps existing type
 */
public record RenderedView(String body, ContentType contentType) {
    public static RenderedView html(String body) {
        return new RenderedView(body, ContentType.TEXT_HTML);
    }
}
