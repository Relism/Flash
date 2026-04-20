package dev.relism.ext.view.thymeleaf;

import dev.relism.http.ContentType;

record ThymeleafTarget(
        Kind kind,
        String template,
        String fragment,
        ContentType contentType
) {
    enum Kind {
        TEMPLATE,
        FRAGMENT
    }
}
