package dev.relism.flash.ext.webbundler;

public enum FrontendType {
    VITE(true),
    STATIC(false);

    private final boolean requiresOrchestration;

    FrontendType(boolean requiresOrchestration) {
        this.requiresOrchestration = requiresOrchestration;
    }

    /** Whether this frontend type needs a dev-server process, package manager, and watch loop. */
    boolean requiresOrchestration() {
        return requiresOrchestration;
    }
}
