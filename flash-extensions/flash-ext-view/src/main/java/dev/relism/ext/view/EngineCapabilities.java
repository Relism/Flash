package dev.relism.ext.view;

/** Engine feature flags used for boot-time validation. */
public record EngineCapabilities(boolean supportsPartialSlot) {
    public static final EngineCapabilities NONE = new EngineCapabilities(false);
}
