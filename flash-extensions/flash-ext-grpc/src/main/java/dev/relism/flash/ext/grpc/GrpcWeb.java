package dev.relism.flash.ext.grpc;

import dev.relism.flash.routing.Cors;
import java.util.ArrayList;
import java.util.List;

/**
 * CORS for browsers calling {@link Grpc} routes over gRPC-Web: {@code POST}, the request headers
 * the gRPC-Web clients send, and the status headers they read.
 *
 * <pre>{@code
 * app.use(GrpcWeb.cors("https://app.example"));
 * app.use(GrpcWeb.cors(List.of("https://app.example"), "authorization"));   // and the app's own headers
 * }</pre>
 */
public final class GrpcWeb {

    private static final List<String> HEADERS = List.of("content-type", "grpc-timeout", "x-grpc-web", "x-user-agent");

    private GrpcWeb() {}

    /** Only {@code origins}; none named means any. */
    public static Cors cors(String... origins) {
        return cors(List.of(origins));
    }

    /** Only {@code origins}, allowing {@code headers} on top of the gRPC-Web ones. */
    public static Cors cors(List<String> origins, String... headers) {
        List<String> allowed = new ArrayList<>(HEADERS);
        allowed.addAll(List.of(headers));
        return Cors.allow(origins.toArray(String[]::new)).methods("POST").headers(allowed.toArray(String[]::new))
                .expose("grpc-status", "grpc-message", "grpc-status-details-bin");
    }
}
