package com.votivo.user_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityManagementConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(IdentityConfiguration.class)
            .withPropertyValues("app.identity.sync-enabled=false", "app.identity.page-size=100", "app.identity.reconcile-interval=PT1H");

    @Test
    void missingManagementSecretDoesNotPreventReadOnlyStartup() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(IdentityManagementProperties.class).clientSecret()).isNull();
        });
    }

    @Test
    void rejectsBlankManagementClientId() {
        context.withPropertyValues("app.identity-management.client-id=")
                .run(application -> assertThat(application).hasFailed());
    }
}
