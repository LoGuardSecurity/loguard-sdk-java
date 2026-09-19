package com.loguard.sdk.exceptions;

public class LoGuardQuotaException extends LoGuardException {
    public LoGuardQuotaException(String message) {
        super(message);
    }

    public LoGuardQuotaException(String message, Throwable cause) {
        super(message, cause);
    }
}
