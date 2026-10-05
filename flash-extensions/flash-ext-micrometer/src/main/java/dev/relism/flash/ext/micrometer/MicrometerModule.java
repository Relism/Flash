package dev.relism.flash.ext.micrometer;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.routing.ExchangeInterceptor;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Arrays;
import java.util.Objects;

/**
 * Records standard {@code http.server.requests} timers in a caller-supplied Micrometer registry.
 * Route templates are used as URI tags; concrete request paths are never retained.
 *
 * <pre>{@code
 * app.install(new MicrometerModule(registry));
 * }</pre>
 */
public final class MicrometerModule implements Module {

    private final MeterRegistry registry;
    private String[] excluded = new String[0];

    public MicrometerModule(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Excludes exact route templates, for example an exporter endpoint. */
    public MicrometerModule exclude(String... paths) {
        excluded = Arrays.copyOf(paths, paths.length);
        return this;
    }

    @Override
    public void install(App app) {
        app.add(MeterRegistry.class, registry)
                .add(ExchangeInterceptor.class, new HttpServerMetrics(registry, excluded));
        if (!(registry instanceof AutoCloseable))
            app.add(RegistryCloser.class, new RegistryCloser(registry));
    }

    private record RegistryCloser(MeterRegistry registry) implements AutoCloseable {
        @Override public void close() { registry.close(); }
    }
}
