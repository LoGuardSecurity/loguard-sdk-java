package com.loguard.sdk.exceptions;

/** Thrown when creating a resource that already exists (e.g. a duplicate alert rule). */
public class LoGuardConflictException extends LoGuardException {
    public LoGuardConflictException(String message) {
        super(message);
    }

    public LoGuardConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
