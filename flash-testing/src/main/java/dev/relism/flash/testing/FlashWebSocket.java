package dev.relism.flash.testing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A WebSocket connected to a {@link FlashTest} server, for asserting on what a Flash endpoint
 * pushes back.
 *
 * <pre>{@code
 * try (FlashWebSocket socket = app.ws("/live")) {
 *     socket.sendText("hello");
 *     assertEquals("echo:hello", socket.awaitText(Duration.ofSeconds(2)));
 * }
 * }</pre>
 *
 * Backed by {@link java.net.http.WebSocket}, so the RFC 6455 handshake, masking and
 * fragmentation are the JDK's, not hand-rolled. Incoming text is queued as it arrives, so a
 * message that lands before {@link #awaitText} is called is not lost.
 */
public final class FlashWebSocket implements AutoCloseable {

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final WebSocket socket;

    FlashWebSocket(HttpClient client, URI uri) {
        this.socket = client.newWebSocketBuilder()
                .buildAsync(uri, new QueueingListener())
                .join();
    }

    /** Sends a whole text message. */
    public FlashWebSocket sendText(String message) {
        socket.sendText(Objects.requireNonNull(message, "message"), true).join();
        return this;
    }

    /**
     * Waits for the next text message.
     *
     * @throws AssertionError if none arrives within {@code timeout}
     */
    public String awaitText(Duration timeout) {
        String message = poll(timeout);
        if (message == null)
            throw new AssertionError("No WebSocket text message within " + timeout);
        return message;
    }

    /**
     * Waits for the server to close the connection and returns its close status code.
     *
     * @throws AssertionError if the server does not close within {@code timeout}
     */
    public int awaitClose(Duration timeout) {
        try {
            return closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException notClosed) {
            throw new AssertionError("WebSocket was not closed within " + timeout, notClosed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted awaiting WebSocket close", interrupted);
        } catch (ExecutionException failure) {
            throw new AssertionError("WebSocket failed before closing", failure.getCause());
        }
    }

    /**
     * Sends a normal close and gives the server a moment to answer it.
     *
     * <p>Never throws: this is cleanup, usually in a try-with-resources, and a connection the
     * server already dropped must not mask the failure the test was actually reporting. Use
     * {@link #awaitClose} when the close itself is what you are asserting on.
     */
    @Override
    public void close() {
        try {
            if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        } catch (RuntimeException alreadyGone) {
            // nothing to close
        }
        closed.completeOnTimeout(WebSocket.NORMAL_CLOSURE, 1, TimeUnit.SECONDS)
              .exceptionally(failure -> WebSocket.NORMAL_CLOSURE)
              .join();
    }

    private String poll(Duration timeout) {
        try {
            return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted awaiting a WebSocket message", interrupted);
        }
    }

    /** Reassembles fragmented text and queues whole messages. */
    private final class QueueingListener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                received.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
