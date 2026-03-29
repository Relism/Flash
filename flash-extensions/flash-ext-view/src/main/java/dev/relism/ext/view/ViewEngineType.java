package dev.relism.ext.view;

/**
 * Managed template engine types supported out-of-the-box by {@link ViewExtension}.
 *
 * <p>Pass one of these constants to {@link ViewExtension#ViewExtension(ViewEngineType)}
 * for zero-boilerplate setup. The extension auto-configures the selected engine with
 * sensible defaults and validates that the required library is on the runtime classpath,
 * throwing a descriptive {@link IllegalStateException} at boot time if it is not.
 *
 * <pre>{@code
 * // Zero-boilerplate — Thymeleaf auto-configured with defaults
 * app.install(new ViewExtension(ViewEngineType.THYMELEAF));
 * }</pre>
 *
 * <h3>Dev mode</h3>
 * Template caching is <b>disabled</b> when either:
 * <ul>
 *   <li>the JVM property {@code flash.env} equals {@code dev} (case-insensitive), or</li>
 *   <li>the environment variable {@code FLASH_ENV} equals {@code dev}.</li>
 * </ul>
 * In all other cases caching is enabled (production default).
 *
 * <h3>Adding your own engine</h3>
 * For unsupported engines, implement {@link ViewEngine} directly and use
 * {@link ViewExtension#ViewExtension(ViewEngine)} instead.
 */
public enum ViewEngineType {

    /**
     * Thymeleaf 3.x — natural HTML templates with server-side rendering.
     *
     * <p>Required dependency (add to your {@code pom.xml}):
     * <pre>{@code
     * <dependency>
     *     <groupId>org.thymeleaf</groupId>
     *     <artifactId>thymeleaf</artifactId>
     *     <version>3.1.2.RELEASE</version>
     * </dependency>
     * }</pre>
     *
     * Default resolver: classpath, prefix {@code /templates/}, suffix {@code .html},
     * mode {@code HTML}, encoding UTF-8.
     */
    THYMELEAF;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Instantiates and configures the {@link ViewEngine} for this type.
     * Called once at {@link ViewExtension#install} time — never on the hot-path.
     *
     * @param cacheEnabled whether the engine should cache compiled templates
     * @throws IllegalStateException if the required library is not on the classpath
     */
    ViewEngine createEngine(boolean cacheEnabled) {
        return switch (this) {
            case THYMELEAF -> createThymeleaf(cacheEnabled);
        };
    }

    // ── Engine factories ──────────────────────────────────────────────────────

    private static ViewEngine createThymeleaf(boolean cacheEnabled) {
        try {
            return new ThymeleafEngine(cacheEnabled);
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException("""
                    Thymeleaf is not on the classpath. \
                    Add the following dependency to your pom.xml:

                        <dependency>
                            <groupId>org.thymeleaf</groupId>
                            <artifactId>thymeleaf</artifactId>
                            <version>3.1.2.RELEASE</version>
                        </dependency>
                    """, e);
        }
    }
}
