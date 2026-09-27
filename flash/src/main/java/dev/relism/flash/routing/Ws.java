package dev.relism.flash.routing;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link dev.relism.flash.websocket.WebSocketHandler} class that upgrades requests at the given
 * path. Found by {@code App.scan}; the route's middleware, security included, runs before the
 * handshake.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Ws {
    String value();
}
