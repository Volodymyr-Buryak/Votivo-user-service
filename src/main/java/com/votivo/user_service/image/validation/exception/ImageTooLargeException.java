package com.votivo.user_service.image.validation.exception;

import java.util.Map;

public class ImageTooLargeException extends ImageValidationException {
    private static final String CODE = "image.too-large";
    private static final String DETAIL = "The image exceeds the maximum allowed file size.";
    private static final String LOG_MESSAGE_TEMPLATE = "Image size (%d bytes) exceeds maximum allowed (%d bytes)";

    public ImageTooLargeException(long actualSize, long maxSize) {
        super(
                CODE,
                DETAIL,
                String.format(LOG_MESSAGE_TEMPLATE, actualSize, maxSize),
                Map.of("actualBytes", actualSize, "maxBytes", maxSize)
        );
    }
}
