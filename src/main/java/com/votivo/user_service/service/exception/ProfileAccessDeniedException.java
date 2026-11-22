package com.votivo.user_service.service.exception;

public class ProfileAccessDeniedException extends RuntimeException {
    public ProfileAccessDeniedException() {
        super("Profile operation is not permitted");
    }
}
