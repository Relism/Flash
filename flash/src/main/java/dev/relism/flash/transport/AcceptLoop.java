package dev.relism.flash.transport;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Single accept-loop body — runs on each of a listener's accept threads. All threads for the
 * same listener block on the same {@link java.net.ServerSocket}; the JVM ensures only one wakes
 * per incoming connection (no thundering herd). Other listeners' accept threads are entirely
 * independent.
 */
@Slf4j
public final class AcceptLoop {

    private AcceptLoop() {
    }

    public static void run(BoundListener listener, ConnectionRunner runner, BooleanSupplier stopped) {
        while (!stopped.getAsBoolean()) {
            try {
                runner.accept(listener.socket().accept(), stopped);
            } catch (IOException e) {
                if (!stopped.getAsBoolean()) log.error("Accept error", e);
            }
        }
    }
}
