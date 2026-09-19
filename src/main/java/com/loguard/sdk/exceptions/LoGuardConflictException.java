package com.loguard.sdk.exceptions;

public class LoGuardConflictException extends LoGuardException {
    public LoGuardConflictException(String message) {
        super(message);
    }

    public LoGuardConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
