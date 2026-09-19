package com.loguard.sdk.exceptions;

/** Thrown when event data or client configuration fails validation before being sent. */
public class LoGuardValidationException extends LoGuardException {
    public LoGuardValidationException(String message) {
        super(message);
    }

    public LoGuardValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
