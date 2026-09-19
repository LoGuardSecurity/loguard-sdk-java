package com.loguard.sdk.exceptions;

public class LoGuardNotFoundException extends LoGuardException {
    public LoGuardNotFoundException(String message) {
        super(message);
    }

    public LoGuardNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
