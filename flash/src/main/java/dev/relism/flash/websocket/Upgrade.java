package dev.relism.flash.websocket;

/**
 * What a WebSocket route answers when its middleware lets the request through: the connection
 * performs the handshake and hands itself to {@code handler}. One instance per route.
 */
public record Upgrade(WebSocketHandler handler) {}
