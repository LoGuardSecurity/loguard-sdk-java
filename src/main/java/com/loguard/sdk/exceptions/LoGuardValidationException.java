package com.loguard.sdk.exceptions;

public class LoGuardValidationException extends LoGuardException {
    public LoGuardValidationException(String message) {
        super(message);
    }

    public LoGuardValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
