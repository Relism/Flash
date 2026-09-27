package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;

import java.util.List;

/**
 * A compiled route, as {@link RouteHook#routed} sees it.
 *
 * @param method     the method it answers; {@code GET} for a WebSocket route
 * @param path       the full path, mount prefix included
 * @param websocket  whether it upgrades to a WebSocket
 * @param handler    the class the app built, or {@code null} for a lambda
 * @param middleware the chain around it, outermost first
 */
public record Route(HttpMethod method, String path, boolean websocket, Class<?> handler,
                    List<Class<? extends Middleware>> middleware) {}
