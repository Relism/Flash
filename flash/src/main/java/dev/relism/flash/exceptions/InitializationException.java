package dev.relism.flash.exceptions;

/**
 * Thrown at boot time when Flash detects a configuration or registration error.
 *
 * <p>Fail-fast: a clear crash at startup is always preferable to a server that
 * starts "empty" and silently drops routes.
 */
public class InitializationException extends RuntimeException {

    public InitializationException(String message) {
        super(message);
    }

    public InitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
