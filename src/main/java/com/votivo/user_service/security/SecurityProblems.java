package com.votivo.user_service.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
public final class SecurityProblems {
    private final JsonMapper mapper;

    public void write(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        String detail = switch (status) {
            case UNAUTHORIZED -> "Authentication is required.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable.";
            default -> "Access is denied.";
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
