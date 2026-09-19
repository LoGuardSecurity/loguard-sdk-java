package com.loguard.sdk.exceptions;

/** Thrown when a requested resource (e.g. an alert rule) does not exist. */
public class LoGuardNotFoundException extends LoGuardException {
    public LoGuardNotFoundException(String message) {
        super(message);
    }

    public LoGuardNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
