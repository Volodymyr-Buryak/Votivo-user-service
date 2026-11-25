package com.votivo.user_service.image.validation.exception;

import java.util.Map;

public class UnsupportedImageFormatException extends ImageValidationException {
    private static final String CODE = "image.unsupported-format";
    private static final String DETAIL = "The image format is not supported.";
    private static final String LOG_MESSAGE_TEMPLATE = "Image format is not allowed: %s";

    public UnsupportedImageFormatException(String formatName) {
        super(
                CODE,
                DETAIL,
                String.format(LOG_MESSAGE_TEMPLATE, formatName),
                Map.of("format", formatName)
        );
    }
}
