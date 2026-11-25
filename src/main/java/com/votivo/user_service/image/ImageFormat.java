package com.votivo.user_service.image;

import lombok.*;
import java.util.Arrays;
import com.votivo.user_service.image.validation.exception.UnsupportedImageFormatException;

@Getter
@RequiredArgsConstructor
public enum ImageFormat {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg");

    private final String extension;
    private final String contentType;

    public static ImageFormat fromFormatName(String formatName) {
        if (formatName == null || formatName.isBlank()) {
            throw new UnsupportedImageFormatException(String.valueOf(formatName));
        }

        return Arrays.stream(values())
                .filter(format -> format.matches(formatName))
                .findFirst()
                .orElseThrow(() -> new UnsupportedImageFormatException(formatName));
    }

    private boolean matches(String formatName) {
        return name().equalsIgnoreCase(formatName) || extension.equalsIgnoreCase(formatName);
    }
}