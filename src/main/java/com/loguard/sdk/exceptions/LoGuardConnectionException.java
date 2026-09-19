package com.loguard.sdk.exceptions;

public class LoGuardConnectionException extends LoGuardException {
    public LoGuardConnectionException(String message) {
        super(message);
    }

    public LoGuardConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
