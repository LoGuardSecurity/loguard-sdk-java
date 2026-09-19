package com.loguard.sdk.exceptions;

/** Thrown when the API key is invalid, missing, or the subscription has expired. */
public class LoGuardAuthException extends LoGuardException {
    public LoGuardAuthException(String message) {
        super(message);
    }

    public LoGuardAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
