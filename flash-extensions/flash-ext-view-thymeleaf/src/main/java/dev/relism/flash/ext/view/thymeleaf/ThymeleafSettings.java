package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.Flash;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Thymeleaf resolver/engine defaults tuned for Flash.
 */
public final class ThymeleafSettings {
    private final String prefix;
    private final String suffix;
    private final TemplateMode mode;
    private final boolean cacheEnabled;
    private final String defaultFragment;

    private ThymeleafSettings(Builder b) {
        this.prefix = b.prefix;
        this.suffix = b.suffix;
        this.mode = b.mode;
        this.cacheEnabled = b.cacheEnabled;
        this.defaultFragment = b.defaultFragment;
    }

    public static Builder builder() {
        return new Builder();
    }

    String prefix() { return prefix; }
    String suffix() { return suffix; }
    TemplateMode mode() { return mode; }
    boolean cacheEnabled() { return cacheEnabled; }
    String defaultFragment() { return defaultFragment; }

    public static final class Builder {
        private String prefix = "/templates/";
        private String suffix = ".html";
        private TemplateMode mode = TemplateMode.HTML;
        private boolean cacheEnabled = !Flash.DEV;
        private String defaultFragment = "content";

        public Builder prefix(String prefix) {
            String p = prefix == null ? "" : prefix.trim();
            if (p.isEmpty()) throw new IllegalArgumentException("prefix must not be blank");
            this.prefix = p.endsWith("/") ? p : p + '/';
            return this;
        }

        public Builder suffix(String suffix) {
            String s = suffix == null ? "" : suffix.trim();
            if (s.isEmpty()) throw new IllegalArgumentException("suffix must not be blank");
            this.suffix = s.startsWith(".") ? s : "." + s;
            return this;
        }

        public Builder mode(TemplateMode mode) {
            this.mode = java.util.Objects.requireNonNull(mode, "mode must not be null");
            return this;
        }

        public Builder cacheEnabled(boolean enabled) {
            this.cacheEnabled = enabled;
            return this;
        }

        public Builder defaultFragment(String fragment) {
            String f = fragment == null ? "" : fragment.trim();
            if (f.isEmpty()) throw new IllegalArgumentException("default fragment must not be blank");
            this.defaultFragment = f;
            return this;
        }

        public ThymeleafSettings build() {
            return new ThymeleafSettings(this);
        }
    }
}
