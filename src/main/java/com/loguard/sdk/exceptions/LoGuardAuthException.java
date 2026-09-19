package com.loguard.sdk.exceptions;

public class LoGuardAuthException extends LoGuardException {
    public LoGuardAuthException(String message) {
        super(message);
    }

    public LoGuardAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
