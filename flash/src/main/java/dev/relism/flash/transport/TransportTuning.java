package dev.relism.flash.transport;

/** Tuning constants shared by {@link ListenerBinder}, {@link ServerLifecycle}, and
 *  {@link ConnectionRunner} — grouped here so the accept-side and connection-side constants
 *  that must stay consistent with each other (e.g. the socket buffer size applied at bind time
 *  and at accept time) are declared exactly once. */
final class TransportTuning {

    private TransportTuning() {
    }

    /**
     * Number of platform threads competing on {@code serverSocket.accept()}.
     * Rule of thumb: number of available CPU cores, capped at 8.
     * More than this rarely helps — accept is cheap; the bottleneck is usually
     * the virtual-thread executor dispatching the connection handler.
     */
    static final int ACCEPT_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 8);

    /**
     * TCP listen backlog. The kernel holds up to this many fully-established
     * (SYN+ACK sent, ACK received) connections waiting for accept().
     * 4096 is safe on Linux; /proc/sys/net/core/somaxconn must be >= this value,
     * or the kernel silently caps it. Raise somaxconn if needed:
     *   sysctl -w net.core.somaxconn=4096
     */
    static final int ACCEPT_BACKLOG = 4096;

    /**
     * Socket send/receive buffer sizes. Matched to the WS frame read buffer
     * ({@code FlashConfiguration#getWsFrameBufferSize()}) so the kernel never
     * needs to fragment a full frame into multiple TCP segments on the receive
     * side, and never blocks a write waiting for the send buffer to drain.
     *
     * Linux default is 87380 bytes (rmem) / 16384 bytes (wmem). We raise both
     * to 256 KB — a good fit for up to ~250 KB WS frames with no partial reads.
     */
    static final int SOCKET_BUF_SIZE = 256 * 1024;
}
