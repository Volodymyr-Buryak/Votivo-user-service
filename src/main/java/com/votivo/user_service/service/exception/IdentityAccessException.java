package com.votivo.user_service.service.exception;

/** Deliberately excludes response bodies, credentials, and bearer tokens. */
public class IdentityAccessException extends RuntimeException {
    public IdentityAccessException(String message) {
        super(message);
    }
}
