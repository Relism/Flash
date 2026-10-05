package dev.relism.flash.ext.micrometer;

import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.ExchangeInterceptor;
import dev.relism.flash.routing.PathUtils;
import dev.relism.flash.routing.Route;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

/** One allocation-free timer lookup and primitive recording after each warmed exchange. */
final class HttpServerMetrics implements ExchangeInterceptor {

    private static final String METER = "http.server.requests";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String[] STATUS = statuses();
    private static final VarHandle METHODS = MethodHandles.arrayElementVarHandle(MethodMeters[].class);
    private static final VarHandle TIMERS = MethodHandles.arrayElementVarHandle(Timer[].class);

    private final MeterRegistry registry;
    private final String[] excluded;

    HttpServerMetrics(MeterRegistry registry, String[] excluded) {
        this.registry = registry;
        this.excluded = new String[excluded.length];
        for (int i = 0; i < excluded.length; i++) this.excluded[i] = PathUtils.sanitize(excluded[i]);
    }

    @Override
    public Handler wrap(Route route, Handler exchange) {
        if (route.websocket() || excluded(route.path())) return exchange;
        Meters meters = new Meters(route.path(), route.method());
        return (request, response) -> record(meters, exchange, request, response);
    }

    @Override
    public Handler wrapFallback(Handler exchange) {
        Meters notFound = new Meters(NOT_FOUND, null);
        Meters methodNotAllowed = new Meters(METHOD_NOT_ALLOWED, null);
        Meters unknown = new Meters(UNKNOWN, null);
        return (request, response) -> {
            long start = System.nanoTime();
            Object answer = exchange.handle(request, response);
            Response actual = answer instanceof Response returned ? returned : response;
            Meters meters = switch (actual.getStatusCode()) {
                case 404 -> notFound;
                case 405 -> methodNotAllowed;
                default -> unknown;
            };
            meters.record(request.method(), actual.getStatusCode(), System.nanoTime() - start);
            return answer;
        };
    }

    private Object record(Meters meters, Handler exchange, Request request, Response response) throws Exception {
        long start = System.nanoTime();
        Object answer = exchange.handle(request, response);
        Response actual = answer instanceof Response returned ? returned : response;
        meters.record(request.method(), actual.getStatusCode(), System.nanoTime() - start);
        return answer;
    }

    private boolean excluded(String path) {
        for (String each : excluded) if (each.equals(path)) return true;
        return false;
    }

    private final class Meters {
        private final String uri;
        private final MethodMeters[] methods = new MethodMeters[HttpMethod.values().length];

        Meters(String uri, HttpMethod warmMethod) {
            this.uri = uri;
            if (warmMethod != null) methods[warmMethod.ordinal()] = new MethodMeters(uri, warmMethod.name());
        }

        void record(HttpMethod method, int status, long nanos) {
            int index = method.ordinal();
            MethodMeters meters = (MethodMeters) METHODS.getAcquire(methods, index);
            if (meters == null) meters = method(index, method.name());
            meters.timer(status).record(nanos, TimeUnit.NANOSECONDS);
        }

        private synchronized MethodMeters method(int index, String name) {
            MethodMeters meters = (MethodMeters) METHODS.getAcquire(methods, index);
            if (meters == null) {
                meters = new MethodMeters(uri, name);
                METHODS.setRelease(methods, index, meters);
            }
            return meters;
        }
    }

    private final class MethodMeters {
        private final String uri;
        private final String method;
        private final Timer[] timers = new Timer[600];

        MethodMeters(String uri, String method) {
            this.uri = uri;
            this.method = method;
        }

        Timer timer(int status) {
            int index = status >= 100 && status < timers.length ? status : 0;
            Timer timer = (Timer) TIMERS.getAcquire(timers, index);
            return timer != null ? timer : create(index, status);
        }

        private synchronized Timer create(int index, int status) {
            Timer timer = (Timer) TIMERS.getAcquire(timers, index);
            if (timer == null) {
                String statusTag = index == 0 ? UNKNOWN : STATUS[status];
                timer = registry.timer(METER, "method", method, "uri", uri,
                        "status", statusTag, "outcome", outcome(status));
                TIMERS.setRelease(timers, index, timer);
            }
            return timer;
        }
    }

    private static String outcome(int status) {
        return switch (status / 100) {
            case 1 -> "INFORMATIONAL";
            case 2 -> "SUCCESS";
            case 3 -> "REDIRECTION";
            case 4 -> "CLIENT_ERROR";
            case 5 -> "SERVER_ERROR";
            default -> UNKNOWN;
        };
    }

    private static String[] statuses() {
        String[] statuses = new String[600];
        for (int i = 100; i < statuses.length; i++) statuses[i] = Integer.toString(i);
        return statuses;
    }
}
