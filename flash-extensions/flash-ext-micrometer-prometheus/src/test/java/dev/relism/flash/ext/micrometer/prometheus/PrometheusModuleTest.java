package dev.relism.flash.ext.micrometer.prometheus;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusModuleTest {

    @RegisterExtension
    final FlashTest app = FlashTest.of(() -> App.create()
            .install(new PrometheusModule())
            .get("/hello/{name}", (request, response) -> "hello"));

    @Test
    void streamsPrometheusMetricsAndExcludesItself() {
        app.get("/hello/ada").expectStatus(200);

        String scrape = app.get("/metrics").expectStatus(200).body();
        assertTrue(scrape.contains("http_server_requests_seconds_count"));
        assertTrue(scrape.contains("uri=\"/hello/{name}\""));

        String nextScrape = app.get("/metrics").expectStatus(200).body();
        assertFalse(nextScrape.contains("uri=\"/metrics\""));
    }
}
