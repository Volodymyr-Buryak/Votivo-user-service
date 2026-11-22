package com.votivo.user_service.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.identity")
@Validated
public record IdentityProperties(
        @URL(regexp = "(?:https?://.*)?")
        String baseUrl,

        String realm,

        String clientId,

        String clientSecret,

        @Positive
        int pageSize,

        @NotNull
        Duration reconcileInterval,

        @DefaultValue("true")
        boolean syncEnabled
) {
    @ConstructorBinding
    public IdentityProperties {
    }

    public IdentityProperties(String baseUrl, String realm, String clientId, String clientSecret,
                              int pageSize, Duration reconcileInterval) {
        this(baseUrl, realm, clientId, clientSecret, pageSize, reconcileInterval, true);
    }

    @AssertTrue(message = "app.identity.reconcile-interval must be positive")
    public boolean isReconcileIntervalPositive() {
        return reconcileInterval == null || reconcileInterval.isPositive();
    }

    @AssertTrue(message = "app.identity base-url, realm, client-id and client-secret are required when synchronization is enabled")
    public boolean isSyncConfigurationValid() {
        return !syncEnabled || StringUtils.hasText(baseUrl) && StringUtils.hasText(realm)
                && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    @Override
    public String toString() {
        return "IdentityProperties[baseUrl=" + baseUrl + ", realm=" + realm
                + ", clientId=" + clientId + ", clientSecret=<redacted>, pageSize="
                + pageSize + ", reconcileInterval=" + reconcileInterval + ", syncEnabled=" + syncEnabled + "]";
    }
}
