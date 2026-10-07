package dev.relism.flash.ext.micrometer.prometheus;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.micrometer.MicrometerModule;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.PathUtils;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Installs Micrometer HTTP metrics and exposes a Prometheus scrape endpoint at {@code /metrics}.
 *
 * <pre>{@code
 * app.install(new PrometheusModule());
 * }</pre>
 */
public final class PrometheusModule implements Module {

    private static final byte[] CONTENT_TYPE =
            "text/plain; version=0.0.4; charset=utf-8".getBytes(StandardCharsets.US_ASCII);

    private final PrometheusMeterRegistry registry;
    private final String path;

    public PrometheusModule() {
        this(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT), "/metrics");
    }

    public PrometheusModule(String path) {
        this(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT), path);
    }

    public PrometheusModule(PrometheusMeterRegistry registry, String path) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.path = PathUtils.sanitize(path);
    }

    @Override
    public void install(App app) {
        app.install(new MicrometerModule(registry).exclude(path));
        app.get(path, this::metrics);
    }

    private Object metrics(dev.relism.flash.http.Request request, Response response) {
        return response.type(CONTENT_TYPE).streaming(registry::scrape);
    }
}
