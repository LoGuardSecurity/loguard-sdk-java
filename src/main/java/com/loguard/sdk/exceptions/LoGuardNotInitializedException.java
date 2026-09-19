package com.loguard.sdk.exceptions;

/** Thrown when an operation is attempted on a client that hasn't been configured yet. */
public class LoGuardNotInitializedException extends LoGuardException {
    public LoGuardNotInitializedException(String message) {
        super(message);
    }

    public LoGuardNotInitializedException(String message, Throwable cause) {
        super(message, cause);
    }
}
