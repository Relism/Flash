package dev.relism.flash.routing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link dev.relism.flash.websocket.WebSocketHandler} at the given path, over HTTP/1.1 and
 * HTTP/2 alike. Middleware runs before the handshake.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Ws {
    String value();
}
