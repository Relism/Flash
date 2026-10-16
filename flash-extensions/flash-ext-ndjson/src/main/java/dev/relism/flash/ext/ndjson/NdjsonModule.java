package dev.relism.flash.ext.ndjson;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.Services;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.serde.Codec;
import java.nio.charset.StandardCharsets;

/**
 * NDJSON for an app: the JSON codec every {@link NdjsonHandler} reads and writes its lines with.
 *
 * <pre>{@code
 * App.create().install(new JsonModule(), new NdjsonModule(Json.class)).scan("com.acme");
 * }</pre>
 *
 * <p>With flash-ext-openapi on the classpath, NDJSON routes are documented too.
 */
public final class NdjsonModule implements Module {

    private final Class<? extends Codec> codec;

    /** @param codec the JSON codec's class, as its module added it: {@code Json.class} */
    public NdjsonModule(Class<? extends Codec> codec) {
        this.codec = codec;
    }

    @Override
    public void install(App app) {
        app.add(NdjsonModule.class, this).install(Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> a -> a.add(OpenApiContributor.class, NdjsonDocs.class)));
    }

    /** The codec, once it is known to write JSON. */
    Codec codec(Services services) {
        Codec found = services.find(codec).orElseThrow(() -> new IllegalStateException("NdjsonModule("
                + codec.getSimpleName() + ".class): no " + codec.getName() + " is installed; install its module"));
        String type = new String(found.contentType().getBytes(), StandardCharsets.US_ASCII);
        if (!type.equals("application/json") && !type.endsWith("+json"))
            throw new IllegalStateException("NdjsonModule(" + codec.getSimpleName() + ".class): NDJSON lines are JSON, and "
                    + codec.getName() + " writes " + type);
        return found;
    }
}
