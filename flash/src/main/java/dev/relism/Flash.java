package dev.relism;

/**
 * Global Flash constants and runtime environment flags.
 *
 * <h3>Dev mode</h3>
 * Set via JVM property {@code -Dflash.env=dev} or environment variable {@code FLASH_ENV=dev}.
 * Checked once at class load — zero runtime overhead.
 *
 * <p>Dev mode enables:
 * <ul>
 *   <li>HTML error pages with full stack traces (default: JSON generic responses in prod)</li>
 *   <li>Template cache disabled in {@code flash-ext-view-thymeleaf}</li>
 * </ul>
 *
 * <p>Extensions can read {@link #DEV} to branch behavior without re-implementing the detection:
 * <pre>{@code
 * if (Flash.DEV) log.debug("Verbose OIDC debug output enabled");
 * }</pre>
 */
public final class Flash {

    private Flash() {}

    public static final String VERSION = "5.0.0-dev";

    /**
     * {@code true} when running in dev mode.
     * Set via {@code -Dflash.env=dev} (JVM property) or {@code FLASH_ENV=dev} (env var).
     * JVM property takes precedence.
     */
    public static final boolean DEV;

    static {
        String prop = System.getProperty("flash.env");
        DEV = "dev".equalsIgnoreCase(prop != null ? prop : System.getenv("FLASH_ENV"));
    }
}
