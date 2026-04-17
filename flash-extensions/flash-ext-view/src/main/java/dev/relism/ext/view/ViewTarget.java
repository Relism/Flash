package dev.relism.ext.view;

/**
 * Resolved render target for one handler class.
 *
 * @param kind page or partial render
 * @param template template identifier/path
 * @param slot optional partial slot selector
 */
public record ViewTarget(
        ViewKind kind,
        String template,
        String slot
) {}
