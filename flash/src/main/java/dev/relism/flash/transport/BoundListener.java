package dev.relism.flash.transport;

import java.net.ServerSocket;

/** One bound listener socket (plain or TLS) plus whether it is TLS, for logging only. */
public record BoundListener(ServerSocket socket, boolean secure) {
}
