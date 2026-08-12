package dev.relism.flash.ext.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link McpResource} subclass as an MCP resource and declares its metadata, discovered
 * by {@link McpConfig#toolsPackage(String)}.
 *
 * <pre>{@code
 * @Resource(uri = "config://app-settings", description = "Application settings")
 * public class AppSettingsResource extends McpResource {
 *     @Override
 *     public ResourceContents read() {
 *         return TextResourceContents.of(uri(), "application/json", settingsJson());
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Resource {
    /** Unique resource URI (used by clients in {@code resources/read}). */
    String uri();

    String name() default "";
    String description() default "";
    String mimeType() default "text/plain";
}
