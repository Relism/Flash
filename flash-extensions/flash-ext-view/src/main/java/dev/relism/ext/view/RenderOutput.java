package dev.relism.ext.view;

import dev.relism.http.ContentType;

/**
 * Render result produced by a {@link ViewEngineAdapter}.
 *
 * @param body rendered response body
 * @param contentType optional explicit response content type; if null runtime falls back to
 *                    route-level default ({@code text/html})
 */
public record RenderOutput(String body, ContentType contentType) {
    public static RenderOutput html(String body) {
        return new RenderOutput(body, ContentType.TEXT_HTML);
    }
}
