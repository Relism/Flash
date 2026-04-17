package dev.relism.ext.view;

public record EngineCapabilities(boolean supportsPartialSlot) {
    public static final EngineCapabilities NONE = new EngineCapabilities(false);
}
