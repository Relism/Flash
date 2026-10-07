package dev.relism.flash.ext.grpc;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.routing.Verb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A gRPC method at its path, {@code /package.Service/Method}. Its handler extends one of the four
 * explicit gRPC handler classes; the boot checks its cardinality and message types against the
 * registered descriptor.
 *
 * <pre>{@code
 * @Grpc("/orders.v1.Orders/Watch")
 * final class Watch extends GrpcServerStreaming<WatchReq, Event> {
 *     @Override protected void handle(Request req, WatchReq in, GrpcOutput<Event> out) {
 *         for (Event e : events.since(in.getCursor())) out.send(e);
 *     }
 * }
 * }</pre>
 */
@Verb(HttpMethod.POST)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Grpc {
    String value();
}
