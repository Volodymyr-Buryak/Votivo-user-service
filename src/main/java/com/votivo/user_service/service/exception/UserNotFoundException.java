package com.votivo.user_service.service.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() {
        super("User profile was not found");
    }
}
