package dev.relism.extension;

/**
 * Contract for all Flash extensions. An extension receives the full {@link FlashApp}
 * so it can both register handlers on the server and expose services through
 * {@link ExtensionContext} for other extensions.
 *
 * <pre>{@code
 * public class OpenApiExtension implements FlashExtension {
 *     public void install(FlashApp app, ExtensionContext ctx) {
 *         ObjectMapper mapper = ctx.require(ObjectMapper.class);
 *         app.get("/openapi.json", (req, res) -> mapper.writeValueAsString(spec));
 *     }
 * }
 *
 * FlashApp.of(new HttpServer(config))
 *     .install(new JacksonExtension())
 *     .install(new OpenApiExtension("/openapi.json"))
 *     .install(new OidcExtension(OidcConfig.fromEnv()));
 * }</pre>
 */
@FunctionalInterface
public interface FlashExtension {
    void install(FlashApp app, ExtensionContext ctx);
}
