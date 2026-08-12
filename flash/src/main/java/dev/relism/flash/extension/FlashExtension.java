package dev.relism.flash.extension;

/**
 * One declarative contribution to a Flash application.
 *
 * <p>Extensions never control lifecycle ordering. During {@link #configure}, they declare
 * services, processors, listeners and ready callbacks. Flash closes declarations, validates and
 * resolves the complete service graph, then executes ready callbacks to materialise routes.
 */
@FunctionalInterface
public interface FlashExtension {
    /**
     * Declares this extension's contribution. {@code ctx.require(...)} is intentionally illegal
     * here: work needing resolved services belongs in {@link FlashContext#onReady(Runnable)}.
     */
    void configure(FlashRegistrar<?> app, FlashContext ctx);
}
