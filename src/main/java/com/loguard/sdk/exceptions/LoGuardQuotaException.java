package com.loguard.sdk.exceptions;

/** Thrown when the account's monthly event quota has been exceeded. */
public class LoGuardQuotaException extends LoGuardException {
    public LoGuardQuotaException(String message) {
        super(message);
    }

    public LoGuardQuotaException(String message, Throwable cause) {
        super(message, cause);
    }
}
