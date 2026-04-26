package dev.relism.flash.ext.view.jte;

import dev.relism.flash.Flash;

import java.nio.file.Path;

/**
 * jte runtime settings with Flash-sensitive defaults.
 */
public final class JteSettings {
    private final String templateRoot;
    private final gg.jte.ContentType contentType;
    private final boolean developmentMode;
    private final boolean usePrecompiled;
    private final boolean binaryStaticContent;
    private final Path dynamicClassesPath;
    private final Path precompiledClassesPath;

    private JteSettings(Builder b) {
        this.templateRoot = b.templateRoot;
        this.contentType = b.contentType;
        this.developmentMode = b.developmentMode;
        this.usePrecompiled = b.usePrecompiled;
        this.binaryStaticContent = b.binaryStaticContent;
        this.dynamicClassesPath = b.dynamicClassesPath;
        this.precompiledClassesPath = b.precompiledClassesPath;
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

    public static final class Builder {
        private String templateRoot = "/templates";
        private gg.jte.ContentType contentType = gg.jte.ContentType.Html;
        private Boolean developmentMode;
        private Boolean usePrecompiled;
        private boolean binaryStaticContent;
        private Path dynamicClassesPath = Path.of("jte-classes");
        private Path precompiledClassesPath = Path.of("jte-classes");

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
            return new JteSettings(resolved);
        }

        private static String normalizeResourceRoot(String root) {
            String normalized = root.replace('\\', '/');
            if (!normalized.startsWith("/")) normalized = '/' + normalized;
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            if (normalized.isEmpty()) return "/";
            return normalized;
        }
    }
}
