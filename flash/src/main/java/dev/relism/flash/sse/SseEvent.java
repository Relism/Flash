package dev.relism.flash.sse;

import java.time.Duration;
import java.util.Objects;

/** One server-sent event and its optional standard metadata. */
public record SseEvent(String data, String event, String id, long retryMillis) {

    public SseEvent {
        Objects.requireNonNull(data, "data");
        oneLine(event, "event");
        oneLine(id, "id");
    }

    public static SseEvent of(String data) {
        return new SseEvent(data, null, null, -1);
    }

    public SseEvent event(String event) {
        return new SseEvent(data, Objects.requireNonNull(event, "event"), id, retryMillis);
    }

    public SseEvent id(String id) {
        return new SseEvent(data, event, Objects.requireNonNull(id, "id"), retryMillis);
    }

    public SseEvent retry(Duration retry) {
        long millis = Objects.requireNonNull(retry, "retry").toMillis();
        if (millis < 0) throw new IllegalArgumentException("retry must not be negative");
        return new SseEvent(data, event, id, millis);
    }

    private static void oneLine(String value, String name) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0))
            throw new IllegalArgumentException(name + " must fit on one line");
    }
}
