package dev.relism.ext.view;

import dev.relism.models.Request;

@FunctionalInterface
public interface ViewGlobals {
    ViewModel provide(Request req);
}
