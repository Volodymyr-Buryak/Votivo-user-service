package com.votivo.user_service.image.validation.exception;

public class InvalidImageException extends ImageValidationException {
    private static final String CODE = "image.invalid";
    private static final String DETAIL = "The file does not contain a valid image.";
    private static final String LOG_MESSAGE = "Invalid image data";

    public InvalidImageException() {
        super(CODE, DETAIL);
    }

    public InvalidImageException(Throwable cause) {
        super(CODE, DETAIL, LOG_MESSAGE, cause);
    }
}