package com.votivo.user_service.image.validation.exception;

import java.util.Map;

public class ImageDimensionsExceededException extends ImageValidationException {
    private static final String CODE = "image.dimensions-exceeded";
    private static final String DETAIL = "The image dimensions exceed the allowed limits.";
    private static final String LOG_MESSAGE_TEMPLATE = "Image dimensions (%dx%dpx) exceed maximum allowed (%dx%dpx)";

    public ImageDimensionsExceededException(int width, int height, int maxWidth, int maxHeight) {
        super(
                CODE,
                DETAIL,
                String.format(LOG_MESSAGE_TEMPLATE, width, height, maxWidth, maxHeight),
                Map.of("width", width, "height", height, "maxWidth", maxWidth, "maxHeight", maxHeight)
        );
    }
}
