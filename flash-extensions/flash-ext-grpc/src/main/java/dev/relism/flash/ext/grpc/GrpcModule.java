package dev.relism.flash.ext.grpc;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.protobuf.Protobuf;
import dev.relism.flash.ext.serde.CodecDuplexHandler;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * gRPC for an app: the service descriptors {@link Grpc} routes are checked against. They are named,
 * not searched for: a service usually lives in a file that only imports its messages, so a search
 * from the messages would find it only some of the time.
 *
 * <pre>{@code
 * App.create()
 *     .install(new ProtobufModule(), new GrpcModule(OrdersProto.getDescriptor()))
 *     .scan("com.acme.orders");
 * }</pre>
 *
 * <p>A method of a registered service that no route answers is reported when the app starts.
 */
@Slf4j
public final class GrpcModule implements Module, RouteHook {

    private final Map<String, MethodDescriptor> methods = new HashMap<>();
    private final Set<String> routed = new HashSet<>();

    public GrpcModule(FileDescriptor... files) {
        for (FileDescriptor file : files)
            for (ServiceDescriptor service : file.getServices())
                for (MethodDescriptor method : service.getMethods())
                    methods.put("/" + service.getFullName() + "/" + method.getName(), method);
    }

    @Override
    public void install(App app) {
        app.add(GrpcModule.class, this).add(RouteHook.class, this).onStart(services -> methods.keySet().stream()
                .filter(path -> !routed.contains(path)).sorted()
                .forEach(path -> log.warn("gRPC method {} has no @Grpc handler", path)));
    }

    @Override
    public void routed(Route route) {
        Class<?> handler = route.handler();
        if (handler == null) return;
        if (!GrpcHandler.class.isAssignableFrom(handler)) {
            if (handler.getAnnotation(Grpc.class) != null)
                throw new IllegalStateException(handler.getSimpleName() + " carries @Grpc but is not a GrpcHandler");
            return;
        }
        MethodDescriptor method = method(route.path());
        Type[] signature = CodecDuplexHandler.signature(handler);
        boolean client = GrpcClientStreaming.class.isAssignableFrom(handler)
                || GrpcBidiStreaming.class.isAssignableFrom(handler);
        boolean server = GrpcServerStreaming.class.isAssignableFrom(handler)
                || GrpcBidiStreaming.class.isAssignableFrom(handler);
        boolean fits = signature[0] == Protobuf.class && signature[2] == Protobuf.class
                && client == method.isClientStreaming() && server == method.isServerStreaming()
                && named(signature[1], method.getInputType()) && named(signature[3], method.getOutputType());
        if (!fits) throw new IllegalStateException(handler.getSimpleName() + ": " + route.path() + " is "
                + kind(method) + " in " + method.getFile().getName() + " ("
                + (method.isClientStreaming() ? "stream " : "") + method.getInputType().getName() + " → "
                + (method.isServerStreaming() ? "stream " : "") + method.getOutputType().getName()
                + "); choose the matching Grpc* handler with those message types");
    }

    private MethodDescriptor method(String path) {
        MethodDescriptor method = methods.get(path);
        if (method == null)
            throw new IllegalStateException("@Grpc " + path + ": no registered service has this method; "
                    + "register its file with new GrpcModule(YourProto.getDescriptor())");
        routed.add(path);
        return method;
    }

    private static String kind(MethodDescriptor method) {
        if (method.isClientStreaming()) return method.isServerStreaming() ? "bidirectional streaming" : "client streaming";
        return method.isServerStreaming() ? "server streaming" : "unary";
    }

    private static boolean named(Type type, Descriptor message) {
        if (!(type instanceof Class<?> target) || !Message.class.isAssignableFrom(target)) return false;
        try {
            return ((Descriptor) target.getMethod("getDescriptor").invoke(null)).getFullName()
                    .equals(message.getFullName());
        } catch (ReflectiveOperationException notGenerated) {
            return false;
        }
    }
}
