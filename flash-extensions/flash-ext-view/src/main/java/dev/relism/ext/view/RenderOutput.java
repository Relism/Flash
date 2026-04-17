package dev.relism.ext.view;

import dev.relism.http.ContentType;

public record RenderOutput(String body, ContentType contentType) {
    public static RenderOutput html(String body) {
        return new RenderOutput(body, ContentType.TEXT_HTML);
    }
}
