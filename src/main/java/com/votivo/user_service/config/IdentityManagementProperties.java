package com.votivo.user_service.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.identity-management")
@Validated
public record IdentityManagementProperties(
        @NotBlank
        @DefaultValue("user-service-management")
        String clientId,

        String clientSecret,

        @DefaultValue("true")
        boolean emailAsUsername
) {
    @Override
    public String toString() {
        return "IdentityManagementProperties[clientId=" + clientId
                + ", clientSecret=<redacted>, emailAsUsername=" + emailAsUsername + "]";
    }
}
