package com.votivo.user_service.service.exception;

public class ProfileConflictException extends RuntimeException {
    public ProfileConflictException() {
        super("Profile update conflicts with an existing identity");
    }
}
