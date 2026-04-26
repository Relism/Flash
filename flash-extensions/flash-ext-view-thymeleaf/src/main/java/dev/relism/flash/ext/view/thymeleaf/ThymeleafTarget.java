package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.http.ContentType;

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
