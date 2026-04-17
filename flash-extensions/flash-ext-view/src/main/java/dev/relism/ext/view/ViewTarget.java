package dev.relism.ext.view;

public record ViewTarget(
        ViewKind kind,
        String template,
        String slot
) {}
