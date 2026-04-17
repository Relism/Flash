package dev.relism.ext.view;

import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.Response;

import java.util.Map;

final class LegacyViewEngineAdapter implements ViewEngineAdapter {
    private static final String DEFAULT_FRAGMENT_SLOT = "content";

    private final ViewEngine engine;

    LegacyViewEngineAdapter(ViewEngine engine) {
        this.engine = engine;
    }

    @Override
    public EngineCapabilities capabilities() {
        return EngineCapabilities.NONE;
    }

    @Override
    public RenderOutput render(ViewTarget target, Map<String, Object> model, Request req, Response res) throws Exception {
        String template = target.template();
        boolean fragment = target.kind() == ViewKind.PARTIAL;

        if (fragment && target.slot() != null && !target.slot().isBlank()) {
            template = template + " :: " + target.slot();
            fragment = false;
        } else if (fragment) {
            template = template + " :: " + DEFAULT_FRAGMENT_SLOT;
            fragment = false;
        }

        return new RenderOutput(engine.render(template, model, fragment), ContentType.TEXT_HTML);
    }
}
