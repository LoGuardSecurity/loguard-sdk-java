package com.loguard.sdk.exceptions;

/** Thrown when the LoGuard API is unreachable, times out, or returns a server error after retries are exhausted. */
public class LoGuardConnectionException extends LoGuardException {
    public LoGuardConnectionException(String message) {
        super(message);
    }

    public LoGuardConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
