package com.loguard.sdk.exceptions;

/**
 * Base exception for all LoGuard SDK errors.
 *
 * Unchecked by design (extends RuntimeException), matching the
 * ergonomics of the other LoGuard SDKs (Python/Node's LoGuardError is
 * an unchecked exception in the idiomatic sense for those languages)
 * and avoiding forcing every caller of event()/eventBatch() to
 * declare `throws` for something that is, in the fire-and-forget
 * common case, meant to be optional to handle.
 */
public class LoGuardException extends RuntimeException {
    public LoGuardException(String message) {
        super(message);
    }

    public LoGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
