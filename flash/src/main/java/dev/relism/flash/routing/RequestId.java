package dev.relism.flash.routing;

import dev.relism.flash.http.Exchange;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A request id on every answer, error answers included: the caller's, when it sends a well-formed one,
 * otherwise one made here, unique in this process.
 *
 * <pre>{@code
 * app.use(RequestId.header("X-Request-Id"));
 * }</pre>
 *
 * <p>Well formed is 1 to 64 ASCII letters, digits or {@code -}: a UUID or a ULID passes, and nothing
 * that could break a log line does.
 */
public final class RequestId implements Middleware {

    private final String name;
    private final String prefix = Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 16) + "-";
    private final AtomicLong next = new AtomicLong();

    private RequestId(String name) {
        this.name = name;
    }

    public static RequestId header(String name) {
        return new RequestId(name);
    }

    @Override
    public void handle(Exchange exchange, Next chain) throws Exception {
        String given = exchange.request().header(name);
        exchange.response().header(name, wellFormed(given) ? given : prefix + Long.toHexString(next.incrementAndGet()));
        chain.handle(exchange);
    }

    static boolean wellFormed(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-')) return false;
        }
        return true;
    }
}
