package com.loguard.sdk.exceptions;

public class LoGuardNotInitializedException extends LoGuardException {
    public LoGuardNotInitializedException(String message) {
        super(message);
    }

    public LoGuardNotInitializedException(String message, Throwable cause) {
        super(message, cause);
    }
}
