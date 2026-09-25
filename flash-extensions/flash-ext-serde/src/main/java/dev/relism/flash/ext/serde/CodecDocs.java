package dev.relism.flash.ext.serde;

import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiContributorRegistry;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.TypedHandler;

import java.lang.reflect.Type;

/**
 * Tells the published document what a {@link CodecHandler} reads and writes, by asking its codecs.
 *
 * <p>A format extension calls {@link #register} once it has provided its codec; the first one in
 * puts the bridge in place and the rest find it there. The media type documented is the one the
 * codec that runs reports, so nothing is declared twice and nothing has to be kept in step.
 */
public final class CodecDocs {

    private CodecDocs() {}

    /** Documents every codec handler's media types, when flash-ext-openapi is on the classpath. */
    public static void register(FlashContext ctx) {
        ctx.onReady(() -> {
            try {
                OpenApi.add(ctx);
            } catch (NoClassDefFoundError absent) {
                // flash-ext-openapi is not on the classpath
            }
        });
    }

    /** The content type of the codec a handler names at {@code index}, or null when it names none. */
    private static ContentType mediaType(FlashContext ctx, Class<?> handler, int index) {
        if (index == 0 && TypedHandler.typeArgument(handler, CodecHandler.class, 1) == Void.class) return null;
        Type argument = TypedHandler.typeArgument(handler, CodecHandler.class, index);
        if (!(argument instanceof Class<?> type) || !Codec.class.isAssignableFrom(type)) return null;
        return ((Codec) ctx.require(type)).contentType();
    }

    /** Isolated so this module loads without flash-ext-openapi on the classpath. */
    private static final class OpenApi {

        static void add(FlashContext ctx) {
            ctx.find(OpenApiContributorRegistry.class).ifPresent(registry -> {
                if (registry.contributors().stream().anyMatch(Codecs.class::isInstance)) return;
                registry.add(new Codecs(ctx));
            });
        }

        private record Codecs(FlashContext ctx) implements OpenApiContributor {
            @Override public OpenApiOperationContribution operationFor(Class<?> handler) {
                if (!CodecHandler.class.isAssignableFrom(handler)) return OpenApiOperationContribution.empty();
                return OpenApiOperationContribution.builder()
                        .consumes(mediaType(ctx, handler, 0))
                        .produces(mediaType(ctx, handler, 2))
                        .build();
            }
        }
    }
}
