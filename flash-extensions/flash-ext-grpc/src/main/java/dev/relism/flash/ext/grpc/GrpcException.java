package dev.relism.flash.ext.grpc;

/**
 * A call that ends with a status of the app's choosing: {@code throw new GrpcException(Status.NOT_FOUND, "order 7")}.
 * Its message travels as {@code grpc-message}.
 */
public class GrpcException extends RuntimeException {

    private final Status status;

    public GrpcException(Status status, String message) {
        super(message);
        this.status = status;
    }

    public Status status() {
        return status;
    }
}
