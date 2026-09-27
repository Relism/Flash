package dev.relism.flash.ext.validation;

import dev.relism.flash.http.HttpException;

import java.util.List;

/**
 * Raised when a value breaks its rules. Extends {@link HttpException} with status 422, so Flash's
 * default exception handler renders it without this module registering anything.
 *
 * <p>Whichever engine found them, the failures reach the caller in one shape.
 */
public final class ValidationException extends HttpException {

    private final transient List<Violation> violations;

    public ValidationException(List<Violation> violations) {
        super(422, describe(violations));
        this.violations = List.copyOf(violations);
    }

    /** The individual failures, in the order the engine reported them. */
    public List<Violation> violations() {
        return violations;
    }

    private static String describe(List<Violation> violations) {
        StringBuilder out = new StringBuilder(32 * violations.size());
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) out.append("; ");
            Violation violation = violations.get(i);
            out.append(violation.path()).append(' ').append(violation.message());
        }
        return out.toString();
    }

    /** One broken rule: where it was, and what it wanted. */
    public record Violation(String path, String message) {}
}
