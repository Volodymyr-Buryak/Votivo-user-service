package com.votivo.user_service.service.exception;

/** A user-scoped lookup returned 404; callers may confirm whether the user was deleted. */
public final class IdentityUserNotFoundException extends IdentityAccessException {
    public IdentityUserNotFoundException() {
        super("Identity provider user was not found");
    }
}
