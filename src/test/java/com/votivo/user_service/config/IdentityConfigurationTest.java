package com.votivo.user_service.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(IdentityConfiguration.class)
            .withPropertyValues("app.identity.base-url=https://identity.test",
                    "app.identity.realm=votivo", "app.identity.client-id=sync",
                    "app.identity.client-secret=secret", "app.identity.page-size=100",
                    "app.identity.reconcile-interval=PT1H");

    @Test
    void bindsEnabledSynchronizationByDefault() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(IdentityProperties.class).syncEnabled()).isTrue();
        });
    }

    @Test
    void disabledSynchronizationNeedsNoCredentials() {
        context.withPropertyValues("app.identity.sync-enabled=false", "app.identity.client-secret=",
                "app.identity.base-url=", "app.identity.realm=", "app.identity.client-id=")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(IdentityProperties.class).syncEnabled()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.identity.client-secret=", "app.identity.realm=",
            "app.identity.client-id=", "app.identity.base-url=", "app.identity.base-url=ftp://identity.test",
            "app.identity.base-url=not-a-url", "app.identity.page-size=0",
            "app.identity.reconcile-interval=PT0S", "app.identity.reconcile-interval=PT-1S"})
    void rejectsInvalidConfigurationAtStartup(String invalidProperty) {
        context.withPropertyValues(invalidProperty)
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void disabledSynchronizationStillValidatesSchedulingBounds() {
        context.withPropertyValues("app.identity.sync-enabled=false", "app.identity.reconcile-interval=PT0S")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void missingReconciliationIntervalFailsValidation() {
        new ApplicationContextRunner().withUserConfiguration(IdentityConfiguration.class)
                .withPropertyValues("app.identity.sync-enabled=false", "app.identity.page-size=100")
                .run(application -> assertThat(application).hasFailed());
    }
}
