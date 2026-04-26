package dev.relism.flash.models;

/**
 * Delegates {@link #handle} to a {@link FunctionalHandler} so lambdas can be used without
 * relying on Lombok-generated constructors (IDE-safe, annotation-processing independent).
 */
public class SimpleHandler extends RequestHandler {

    private final FunctionalHandler delegate;

    public SimpleHandler(FunctionalHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        return delegate.handle(request, response);
    }

    /**
     * Functional interface for handling requests, used by {@link SimpleHandler}.
     */
    @FunctionalInterface
    public interface FunctionalHandler {
        Object handle(Request request, Response response) throws Exception;
    }
}
