package dev.relism.models;

import lombok.RequiredArgsConstructor;

/**
 * A concrete implementation of {@link RequestHandler} that delegates to a functional interface.
 * This allows the use of lambdas while keeping the base {@link RequestHandler} as an abstract class.
 */
@RequiredArgsConstructor
public class SimpleHandler extends RequestHandler {

    private final FunctionalHandler delegate;

    @Override
    public Object handle(Request request, Response response) {
        return delegate.handle(request, response);
    }

    /**
     * Functional interface for handling requests, used by {@link SimpleHandler}.
     */
    @FunctionalInterface
    public interface FunctionalHandler {
        Object handle(Request request, Response response);
    }
}
