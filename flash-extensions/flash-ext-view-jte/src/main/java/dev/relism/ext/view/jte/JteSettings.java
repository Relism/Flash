package dev.relism.ext.view.jte;

import dev.relism.Flash;

import java.nio.file.Path;

/**
 * jte runtime settings with Flash-sensitive defaults.
 */
public final class JteSettings {
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 64L * 1024L;

    private final String templateRoot;
    private final gg.jte.ContentType contentType;
    private final boolean developmentMode;
    private final boolean usePrecompiled;
    private final boolean binaryStaticContent;
    private final Path dynamicClassesPath;
    private final Path precompiledClassesPath;
    private final boolean serveStatics;
    private final String staticPrefix;
    private final long largeFileThresholdBytes;
    private final boolean staticCorsEnabled;
    private final String staticCorsAllowOrigin;
    private final String staticCorsAllowMethods;
    private final String staticCorsAllowHeaders;
    private final int staticCorsMaxAge;

    private JteSettings(Builder b) {
        this.templateRoot = b.templateRoot;
        this.contentType = b.contentType;
        this.developmentMode = b.developmentMode;
        this.usePrecompiled = b.usePrecompiled;
        this.binaryStaticContent = b.binaryStaticContent;
        this.dynamicClassesPath = b.dynamicClassesPath;
        this.precompiledClassesPath = b.precompiledClassesPath;
        this.serveStatics = b.serveStatics;
        this.staticPrefix = b.staticPrefix;
        this.largeFileThresholdBytes = b.largeFileThresholdBytes;
        this.staticCorsEnabled = b.staticCorsEnabled;
        this.staticCorsAllowOrigin = b.staticCorsAllowOrigin;
        this.staticCorsAllowMethods = b.staticCorsAllowMethods;
        this.staticCorsAllowHeaders = b.staticCorsAllowHeaders;
        this.staticCorsMaxAge = b.staticCorsMaxAge;
    }

    public static Builder builder() {
        return new Builder();
    }

    String templateRoot() { return templateRoot; }
    gg.jte.ContentType contentType() { return contentType; }
    boolean developmentMode() { return developmentMode; }
    boolean usePrecompiled() { return usePrecompiled; }
    boolean binaryStaticContent() { return binaryStaticContent; }
    Path dynamicClassesPath() { return dynamicClassesPath; }
    Path precompiledClassesPath() { return precompiledClassesPath; }
    boolean serveStatics() { return serveStatics; }
    String staticPrefix() { return staticPrefix; }
    long largeFileThresholdBytes() { return largeFileThresholdBytes; }
    boolean staticCorsEnabled() { return staticCorsEnabled; }
    String staticCorsAllowOrigin() { return staticCorsAllowOrigin; }
    String staticCorsAllowMethods() { return staticCorsAllowMethods; }
    String staticCorsAllowHeaders() { return staticCorsAllowHeaders; }
    int staticCorsMaxAge() { return staticCorsMaxAge; }

    Builder toBuilder() {
        Builder b = new Builder();
        b.templateRoot = templateRoot;
        b.contentType = contentType;
        b.developmentMode = developmentMode;
        b.usePrecompiled = usePrecompiled;
        b.binaryStaticContent = binaryStaticContent;
        b.dynamicClassesPath = dynamicClassesPath;
        b.precompiledClassesPath = precompiledClassesPath;
        b.serveStatics = serveStatics;
        b.staticPrefix = staticPrefix;
        b.largeFileThresholdBytes = largeFileThresholdBytes;
        b.staticCorsEnabled = staticCorsEnabled;
        b.staticCorsAllowOrigin = staticCorsAllowOrigin;
        b.staticCorsAllowMethods = staticCorsAllowMethods;
        b.staticCorsAllowHeaders = staticCorsAllowHeaders;
        b.staticCorsMaxAge = staticCorsMaxAge;
        return b;
    }

    public static final class Builder {
        private String templateRoot = "/templates";
        private gg.jte.ContentType contentType = gg.jte.ContentType.Html;
        private Boolean developmentMode;
        private Boolean usePrecompiled;
        private boolean binaryStaticContent;
        private Path dynamicClassesPath = Path.of("jte-classes");
        private Path precompiledClassesPath = Path.of("jte-classes");
        private boolean serveStatics = true;
        private String staticPrefix = "/static";
        private long largeFileThresholdBytes = DEFAULT_LARGE_FILE_THRESHOLD_BYTES;
        private boolean staticCorsEnabled;
        private String staticCorsAllowOrigin = "*";
        private String staticCorsAllowMethods = "GET,HEAD,OPTIONS";
        private String staticCorsAllowHeaders = "*";
        private int staticCorsMaxAge = 3600;

        public Builder templateRoot(String templateRoot) {
            String root = templateRoot == null ? "" : templateRoot.trim();
            if (root.isEmpty()) throw new IllegalArgumentException("templateRoot must not be blank");
            this.templateRoot = normalizeResourceRoot(root);
            return this;
        }

        public Builder contentType(gg.jte.ContentType contentType) {
            this.contentType = java.util.Objects.requireNonNull(contentType, "contentType must not be null");
            return this;
        }

        public Builder developmentMode(boolean developmentMode) {
            this.developmentMode = developmentMode;
            return this;
        }

        public Builder usePrecompiled(boolean usePrecompiled) {
            this.usePrecompiled = usePrecompiled;
            return this;
        }

        public Builder binaryStaticContent(boolean binaryStaticContent) {
            this.binaryStaticContent = binaryStaticContent;
            return this;
        }

        public Builder dynamicClassesPath(Path dynamicClassesPath) {
            this.dynamicClassesPath = java.util.Objects.requireNonNull(dynamicClassesPath, "dynamicClassesPath must not be null");
            return this;
        }

        public Builder precompiledClassesPath(Path precompiledClassesPath) {
            this.precompiledClassesPath = java.util.Objects.requireNonNull(precompiledClassesPath, "precompiledClassesPath must not be null");
            return this;
        }

        public Builder serveStatics(boolean serveStatics) {
            this.serveStatics = serveStatics;
            return this;
        }

        public Builder staticPrefix(String staticPrefix) {
            this.staticPrefix = normalizeStaticPrefix(staticPrefix);
            return this;
        }

        public Builder largeFileThresholdBytes(long largeFileThresholdBytes) {
            if (largeFileThresholdBytes <= 0) {
                throw new IllegalArgumentException("largeFileThresholdBytes must be > 0");
            }
            this.largeFileThresholdBytes = largeFileThresholdBytes;
            return this;
        }

        public Builder enableStaticCors(boolean enabled) {
            this.staticCorsEnabled = enabled;
            return this;
        }

        public Builder staticCorsAllowOrigin(String allowOrigin) {
            this.staticCorsAllowOrigin = requireCorsValue(allowOrigin, "staticCorsAllowOrigin");
            return this;
        }

        public Builder staticCorsAllowMethods(String allowMethods) {
            this.staticCorsAllowMethods = requireCorsValue(allowMethods, "staticCorsAllowMethods");
            return this;
        }

        public Builder staticCorsAllowHeaders(String allowHeaders) {
            this.staticCorsAllowHeaders = requireCorsValue(allowHeaders, "staticCorsAllowHeaders");
            return this;
        }

        public Builder staticCorsMaxAge(int maxAgeSeconds) {
            if (maxAgeSeconds < 0) {
                throw new IllegalArgumentException("staticCorsMaxAge must be >= 0");
            }
            this.staticCorsMaxAge = maxAgeSeconds;
            return this;
        }

        public JteSettings build() {
            boolean resolvedDev = developmentMode != null ? developmentMode : Flash.DEV;
            boolean resolvedPrecompiled = usePrecompiled != null ? usePrecompiled : !resolvedDev;
            Builder resolved = new Builder();
            resolved.templateRoot = this.templateRoot;
            resolved.contentType = this.contentType;
            resolved.developmentMode = resolvedDev;
            resolved.usePrecompiled = resolvedPrecompiled;
            resolved.binaryStaticContent = this.binaryStaticContent;
            resolved.dynamicClassesPath = this.dynamicClassesPath;
            resolved.precompiledClassesPath = this.precompiledClassesPath;
            resolved.serveStatics = this.serveStatics;
            resolved.staticPrefix = this.staticPrefix;
            resolved.largeFileThresholdBytes = this.largeFileThresholdBytes;
            resolved.staticCorsEnabled = this.staticCorsEnabled;
            resolved.staticCorsAllowOrigin = this.staticCorsAllowOrigin;
            resolved.staticCorsAllowMethods = this.staticCorsAllowMethods;
            resolved.staticCorsAllowHeaders = this.staticCorsAllowHeaders;
            resolved.staticCorsMaxAge = this.staticCorsMaxAge;
            return new JteSettings(resolved);
        }

        private static String normalizeResourceRoot(String root) {
            String normalized = root.replace('\\', '/');
            if (!normalized.startsWith("/")) normalized = '/' + normalized;
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            if (normalized.isEmpty()) return "/";
            return normalized;
        }

        private static String normalizeStaticPrefix(String prefix) {
            String normalized = prefix == null ? "" : prefix.trim().replace('\\', '/');
            if (normalized.isEmpty()) throw new IllegalArgumentException("staticPrefix must not be blank");
            if (!normalized.startsWith("/")) normalized = '/' + normalized;
            while (normalized.length() > 1 && normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }

        private static String requireCorsValue(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }
    }
}
