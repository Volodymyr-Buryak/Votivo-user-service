package com.votivo.user_service.service.exception;

public class PollServiceUnavailableException extends RuntimeException {
    public PollServiceUnavailableException() { super("Poll Service could not confirm account deletion"); }
}
