package dev.relism.exceptions;

public class DuplicateNamespaceException extends RuntimeException {
    public DuplicateNamespaceException(String namespace) {
        super("Router with namespace '" + namespace + "' is already registered.");
    }
}
