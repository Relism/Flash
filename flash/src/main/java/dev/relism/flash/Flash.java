package dev.relism.flash;

import dev.relism.flash.routing.Middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Global Flash constants and runtime environment flags.
 *
 * <h3>Dev mode</h3>
 * Set via JVM property {@code -Dflash.env=dev} or environment variable {@code FLASH_ENV=dev}.
 * Checked once at classloading — zero runtime overhead.
 *
 * <p>Dev mode enables:
 * <ul>
 *   <li>HTML error pages with full stack traces (default: JSON generic responses in prod)</li>
 *   <li>Template cache disabled in {@code flash-ext-view-thymeleaf}</li>
 * </ul>
 *
 */
public final class Flash {

    private Flash() {}

    public static final String VERSION = "5.0.0-dev";
    public static final Commons commons;

    public static final boolean DEV;

    static {
        String prop = System.getProperty("flash.env");
        DEV = "dev".equalsIgnoreCase(prop != null ? prop : System.getenv("FLASH_ENV"));
        commons = new Commons();
    }

    public static class Commons {
        public final Middlewares middlewares = new Middlewares();
    }

    public static class Middlewares {

        public static class CorsConfig {
            private String origin = "*";
            private List<String> methods = new ArrayList<>(List.of("GET","POST","PUT","DELETE","OPTIONS"));
            private List<String> headers = new ArrayList<>(List.of("*"));
            private boolean credentials = false;

            public CorsConfig origin(String v) { this.origin = v; return this; }
            public CorsConfig methods(String... v) { this.methods = new ArrayList<>(List.of(v)); return this; }
            public CorsConfig headers(String... v) { this.headers = new ArrayList<>(List.of(v)); return this; }
            public CorsConfig credentials(boolean v) { this.credentials = v; return this; }

            public String origin() { return origin; }
            public List<String> methods() { return methods; }
            public List<String> headers() { return headers; }
            public boolean credentials() { return credentials; }
        }

        public Middleware cors(CorsConfig config) {
            return next -> (req, res) -> {
                res.header("Access-Control-Allow-Origin", config.origin())
                        .header("Access-Control-Allow-Methods", String.join(", ", config.methods()));

                if (!config.headers().isEmpty()) {
                    res.header("Access-Control-Allow-Headers", String.join(", ", config.headers()));
                }
                if (config.credentials()) {
                    res.header("Access-Control-Allow-Credentials", "true");
                }
                return next.handle(req, res);
            };
        }

        // stile lambda
        public Middleware cors(Consumer<CorsConfig> fn) {
            CorsConfig cfg = new CorsConfig(); // default già settati
            fn.accept(cfg);
            return cors(cfg);
        }

        // default
        public Middleware cors() {
            return cors(new CorsConfig());
        }
    }
}