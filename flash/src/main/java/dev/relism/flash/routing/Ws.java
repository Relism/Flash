package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link dev.relism.flash.models.RequestHandler} subclass as a
 * WebSocket endpoint. Resolved at boot time by {@link Routes#of} via the
 * existing meta-annotation mechanism — no additional reflection required.
 *
 * <pre>{@code
 * @Ws("/chat")
 * public class ChatHandler extends RequestHandler { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Route(method = HttpMethod.GET, path = "")
public @interface Ws {
    String value();
}
