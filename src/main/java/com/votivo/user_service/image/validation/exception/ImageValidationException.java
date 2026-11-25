package com.votivo.user_service.image.validation.exception;

import java.util.Map;
import lombok.Getter;

@Getter
public abstract class ImageValidationException extends RuntimeException {
    private final String code;
    private final String detail;
    private final Map<String, Object> arguments;

    protected ImageValidationException(String code, String detail) {
        this(code, detail, detail, Map.of());
    }

    protected ImageValidationException(String code, String detail, String logMessage, Map<String, Object> arguments) {
        super(logMessage);
        this.code = code;
        this.detail = detail;
        this.arguments = Map.copyOf(arguments);
    }

    protected ImageValidationException(String code, String detail, String logMessage, Throwable cause) {
        super(logMessage, cause);
        this.code = code;
        this.detail = detail;
        this.arguments = Map.of();
    }
}
