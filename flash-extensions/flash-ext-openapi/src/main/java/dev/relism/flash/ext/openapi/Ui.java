package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The documentation page served beside the spec: Swagger UI, Redoc or Scalar. Each knows its own
 * bundle and its own options; what they share — the page around it, the custom stylesheet, the
 * escape hatch for an option this class does not name — lives here.
 *
 * <pre>{@code
 * new OpenApiModule("/openapi", "My API", "1.0.0")
 *     .ui(Ui.scalar().theme(Ui.Scalar.Theme.PURPLE).layout(Ui.Scalar.Layout.CLASSIC).darkMode(true));
 * }</pre>
 *
 * <p>Options are the upstream ones, spelled as the upstream documents them, and land in the
 * configuration object verbatim. The typed methods cover what is worth naming; anything else goes
 * through {@link #option(String, Object)}, including whole nested objects (Redoc's {@code theme}).
 *
 * <p>The bundle comes from jsDelivr at a pinned version. {@link #cdn(String)} points that
 * elsewhere — a mirror, a proxy, or the files served by the application itself.
 *
 * @param <S> the concrete UI, so every setter chains whatever the order
 */
public abstract class Ui<S extends Ui<S>> {

    /** Swagger UI: the one that lets you fire requests from the page. */
    public static Swagger swagger() { return new Swagger(); }

    /** Redoc: three panels, read-only, the most printable of the three. */
    public static Redoc redoc() { return new Redoc(); }

    /** Scalar: the modern one, with themes and an API client built in. */
    public static Scalar scalar() { return new Scalar(); }

    /** No page at all — the spec routes stay. {@code null} is the absence of a UI, and this names it. */
    public static Ui<?> none() { return null; }

    /** What ends up in the configuration object, in declaration order. */
    final Map<String, Object> config = new LinkedHashMap<>();
    private String cdn;
    private String css = "";

    Ui(String cdn) { this.cdn = cdn; }

    /** Where the bundle is loaded from, without a trailing slash. Default: jsDelivr, at a pinned version. */
    public final S cdn(String cdn) {
        this.cdn = cdn.endsWith("/") ? cdn.substring(0, cdn.length() - 1) : cdn;
        return self();
    }

    /** CSS appended to the page, after the bundle's own. */
    public final S customCss(String css) {
        this.css = css;
        return self();
    }

    /** Any option this class does not name, as its upstream documentation spells it; {@code null} drops it. */
    public final S option(String name, Object value) {
        if (value == null) config.remove(name); else config.put(name, value);
        return self();
    }

    /**
     * The whole page, rendered once at boot and served as bytes from then on.
     *
     * @param specPath where the JSON document is served, which every one of the three reads from
     */
    final byte[] page(String specPath, String title, ObjectMapper json) {
        config.put("url", specPath);
        return ("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title>" + head()
                + "<style>body{margin:0}" + css + "</style></head><body>"
                + body(configuration(json)) + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    /** An option that Jackson cannot write is one the application passed: a mistake, and a boot-time one. */
    private String configuration(ObjectMapper json) {
        try {
            return json.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the " + getClass().getSimpleName() + " options as JSON", e);
        }
    }

    /** What the bundle needs in {@code <head>}: a stylesheet, for the one that has one. */
    String head() { return ""; }

    /** The mount point, the bundle, and the one call that starts it on {@code config}. */
    abstract String body(String config);

    final String cdn() { return cdn; }

    @SuppressWarnings("unchecked")
    private S self() { return (S) this; }

    /** {@code SOME_NAME} as the wire spells it: {@code some-name} or {@code somename}. */
    private static String wire(Enum<?> value, char separator) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', separator);
    }

    // ── Swagger UI ────────────────────────────────────────────────────────────

    /** <a href="https://swagger.io/tools/swagger-ui/">Swagger UI</a>, pinned to 5.x. */
    public static final class Swagger extends Ui<Swagger> {

        /** How much of the document is open when the page loads. */
        public enum Expand { LIST, FULL, NONE }

        /** The highlighter's palette. */
        public enum Syntax { AGATE, ARTA, MONOKAI, NORD, OBSIDIAN, TOMORROW_NIGHT, IDEA }

        private Swagger() {
            super("https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.33.0");
            config.put("dom_id", "#ui");
            config.put("deepLinking", true);
        }

        /** Tags open, everything open, or nothing. Default: {@link Expand#LIST}. */
        public Swagger docExpansion(Expand expand) { return option("docExpansion", wire(expand, '-')); }

        /** How deep the models open; {@code -1} hides them. Default: 1. */
        public Swagger modelsExpandDepth(int depth) { return option("defaultModelsExpandDepth", depth); }

        /** Whether a tag or an operation gets its own URL. Default: on, unlike upstream. */
        public Swagger deepLinking(boolean deepLinking) { return option("deepLinking", deepLinking); }

        /** The box that filters operations by tag. */
        public Swagger filter(boolean filter) { return option("filter", filter); }

        /** Whether "Try it out" is already on. */
        public Swagger tryItOut(boolean tryItOut) { return option("tryItOutEnabled", tryItOut); }

        /** Whether credentials entered in the page survive a reload. */
        public Swagger persistAuthorization(boolean persist) { return option("persistAuthorization", persist); }

        public Swagger syntaxTheme(Syntax theme) { return option("syntaxHighlight", Map.of("theme", wire(theme, '-'))); }

        /** Sorts tags and operations by name rather than by the order the document lists them. */
        public Swagger sortAlphabetically(boolean sorted) {
            String sorter = sorted ? "alpha" : null;
            return option("tagsSorter", sorter).option("operationsSorter", sorter);
        }

        @Override String head() { return "<link rel=\"stylesheet\" href=\"" + cdn() + "/swagger-ui.css\">"; }

        @Override String body(String config) {
            return "<div id=\"ui\"></div><script src=\"" + cdn() + "/swagger-ui-bundle.js\"></script>"
                    + "<script>SwaggerUIBundle(" + config + ")</script>";
        }
    }

    // ── Redoc ─────────────────────────────────────────────────────────────────

    /** <a href="https://github.com/Redocly/redoc">Redoc</a> community edition, pinned to 2.x. */
    public static final class Redoc extends Ui<Redoc> {

        private Redoc() { super("https://cdn.jsdelivr.net/npm/redoc@2.5.4"); }

        public Redoc hideDownloadButton(boolean hide) { return option("hideDownloadButton", hide); }

        public Redoc disableSearch(boolean disable) { return option("disableSearch", disable); }

        /** Required properties first in every schema, rather than in declaration order. */
        public Redoc requiredPropsFirst(boolean first) { return option("requiredPropsFirst", first); }

        public Redoc sortPropsAlphabetically(boolean sorted) { return option("sortPropsAlphabetically", sorted); }

        /** How deep a JSON sample is expanded; {@code 0} collapses it. Default: 2. */
        public Redoc jsonSampleExpandLevel(int level) { return option("jsonSampleExpandLevel", level); }

        public Redoc hideSchemaTitles(boolean hide) { return option("hideSchemaTitles", hide); }

        /** The path in the middle panel rather than beside the description. */
        public Redoc pathInMiddlePanel(boolean middle) { return option("pathInMiddlePanel", middle); }

        /** Hides the host the servers declare, leaving the paths. */
        public Redoc hideHostname(boolean hide) { return option("hideHostname", hide); }

        /** The browser's own scrollbars in the sidebar. */
        public Redoc nativeScrollbars(boolean enabled) { return option("nativeScrollbars", enabled); }

        /** Whether an open sidebar group can be collapsed again. Default: on. */
        public Redoc menuToggle(boolean toggle) { return option("menuToggle", toggle); }

        @Override String body(String config) {
            return "<div id=\"ui\"></div><script src=\"" + cdn() + "/bundles/redoc.standalone.js\"></script>"
                    + "<script>var c=" + config + ";Redoc.init(c.url,c,document.getElementById('ui'))</script>";
        }
    }

    // ── Scalar ────────────────────────────────────────────────────────────────

    /** <a href="https://scalar.com/">Scalar</a>'s API reference, pinned to 1.x. */
    public static final class Scalar extends Ui<Scalar> {

        /** Scalar's own palettes. {@link #NONE} leaves the page unstyled, for {@link #customCss(String)}. */
        public enum Theme {
            DEFAULT, ALTERNATE, MOON, PURPLE, SOLARIZED, BLUE_PLANET("bluePlanet"), SATURN, KEPLER, MARS,
            DEEP_SPACE("deepSpace"), LASERWAVE, NONE;

            private final String wire;

            Theme() { this.wire = name().toLowerCase(Locale.ROOT); }
            Theme(String wire) { this.wire = wire; }
        }

        /** Sidebar and content in two columns, or the one-column classic. */
        public enum Layout { MODERN, CLASSIC }

        /** What decides the order of the operations in a tag. */
        public enum Sort { ALPHA, METHOD }

        private Scalar() {
            super("https://cdn.jsdelivr.net/npm/@scalar/api-reference@1.71.0");
            // Upstream shows a Configure/Share/Deploy toolbar on localhost, which is Scalar's product,
            // not this application's: a page served by Flash is the same page everywhere.
            config.put("showDeveloperTools", "never");
        }

        public Scalar theme(Theme theme) { return option("theme", theme.wire); }

        public Scalar layout(Layout layout) { return option("layout", wire(layout, '-')); }

        /** Dark on load; the reader's toggle still wins unless {@link #hideDarkModeToggle(boolean)}. */
        public Scalar darkMode(boolean dark) { return option("darkMode", dark); }

        public Scalar hideDarkModeToggle(boolean hide) { return option("hideDarkModeToggle", hide); }

        /** Whether {@code components.schemas} gets a section of its own. */
        public Scalar hideModels(boolean hide) { return option("hideModels", hide); }

        public Scalar hideSearch(boolean hide) { return option("hideSearch", hide); }

        /** The button that fires the request from the page. */
        public Scalar hideTestRequestButton(boolean hide) { return option("hideTestRequestButton", hide); }

        /** The button that opens the operation in Scalar's API client. */
        public Scalar hideClientButton(boolean hide) { return option("hideClientButton", hide); }

        public Scalar showSidebar(boolean show) { return option("showSidebar", show); }

        /** Every tag open on load, rather than only the first. */
        public Scalar defaultOpenAllTags(boolean open) { return option("defaultOpenAllTags", open); }

        public Scalar sortOperationsBy(Sort sort) { return option("operationsSorter", wire(sort, '-')); }

        @Override String body(String config) {
            return "<div id=\"ui\"></div><script src=\"" + cdn() + "\"></script>"
                    + "<script>Scalar.createApiReference('#ui'," + config + ")</script>";
        }
    }
}
