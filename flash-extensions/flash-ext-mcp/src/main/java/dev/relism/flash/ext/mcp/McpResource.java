package dev.relism.flash.ext.mcp;

/**
 * One MCP resource: metadata on {@link Resource @Resource}, services through the constructor, the
 * hot path in {@link #read()}.
 *
 * <pre>{@code
 * @Resource(uri = "config://app-settings", description = "Application settings", mimeType = "application/json")
 * public final class AppSettingsResource implements McpResource {
 *     @Override public ResourceContents read() {
 *         return TextResourceContents.of(uri(), "application/json", settingsJson());
 *     }
 * }
 * }</pre>
 */
public interface McpResource {

    ResourceContents read() throws Exception;

    /** Runs once at boot, after construction: work that belongs off the hot path. */
    default void onInit() {}

    /** The URI its {@link Resource @Resource} declares. */
    default String uri() {
        Resource declared = getClass().getAnnotation(Resource.class);
        return declared == null ? null : declared.uri();
    }
}
