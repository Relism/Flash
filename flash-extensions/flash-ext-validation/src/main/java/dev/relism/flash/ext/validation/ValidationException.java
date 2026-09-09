package dev.relism.flash.ext.validation;

import dev.relism.flash.exceptions.HttpException;

import java.util.List;

/**
 * Raised when a value fails its constraints. Extends {@link HttpException} with status 422, so
 * Flash's default exception handler renders it without this extension registering anything.
 *
 * <p>Allocated only on failure — a passing validation constructs nothing.
 */
public final class ValidationException extends HttpException {

    private final transient List<Violation> violations;

    ValidationException(List<Violation> violations) {
        super(422, describe(violations));
        this.violations = List.copyOf(violations);
    }

    /** The individual failures, in field declaration order. */
    public List<Violation> violations() {
        return violations;
    }

    private static String describe(List<Violation> violations) {
        StringBuilder out = new StringBuilder(32 * violations.size());
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) out.append("; ");
            Violation v = violations.get(i);
            out.append(v.field()).append(' ').append(v.message());
        }
        return out.toString();
    }

    /** One failed constraint. */
    public record Violation(String field, String message) {}
}
