package com.votivo.user_service.security.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.security")
@Validated
public record SecurityProperties(
        @NotBlank
        @URL(regexp = "https?://.*")
        @DefaultValue("http://localhost:18080/realms/votivo")
        String issuerUri,

        @NotBlank
        @URL(regexp = "https?://.*")
        @DefaultValue("http://localhost:18080/realms/votivo/protocol/openid-connect/certs")
        String jwkSetUri,

        @NotBlank
        @DefaultValue("user-service")
        String audience,

        @NotEmpty
        @DefaultValue("http://localhost:4200")
        List<@NotBlank String> allowedOrigins,

        @Positive
        @DefaultValue("100000")
        int dpopReplayCacheSize,

        @Positive
        @DefaultValue("1000")
        int dpopMaxRequestsPerKey
) {
}
