package com.votivo.user_service.config;

import com.votivo.user_service.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsAreValid() {
        context.run(application -> assertThat(application).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.security.issuer-uri=not-a-url",
            "app.security.jwk-set-uri=ftp://identity.test/keys", "app.security.audience=",
            "app.security.dpop-replay-cache-size=0", "app.security.dpop-max-requests-per-key=0"})
    void rejectsInvalidSecurityProperties(String invalidProperty) {
        context.withPropertyValues(invalidProperty)
                .run(application -> assertThat(application).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
