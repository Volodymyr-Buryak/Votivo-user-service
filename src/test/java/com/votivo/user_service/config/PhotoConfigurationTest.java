package com.votivo.user_service.config;

import com.votivo.user_service.image.ImageFormat;
import com.votivo.user_service.image.validation.ImageValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PhotoConfiguration.class)
            .withPropertyValues("app.photo.max-size=5MB", "app.photo.max-width-pixels=4096",
                    "app.photo.max-height-pixels=4096", "app.photo.allowed-formats=JPEG,PNG");

    @Test
    void bindsLimitsAndCreatesValidator() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(ImageValidator.class);
            var properties = application.getBean(PhotoProperties.class);
            assertThat(properties.maxSize().toBytes()).isEqualTo(5242880);
            assertThat(properties.allowedFormats()).containsExactlyInAnyOrder(ImageFormat.JPEG, ImageFormat.PNG);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.photo.max-size=0B", "app.photo.max-size=-1B", "app.photo.max-size=2GB",
            "app.photo.max-width-pixels=0", "app.photo.max-height-pixels=-1",
            "app.photo.allowed-formats=", "app.photo.allowed-formats=GIF"})
    void rejectsInvalidConfigurationAtStartup(String invalidProperty) {
        context.withPropertyValues(invalidProperty).run(application -> assertThat(application).hasFailed());
    }
}
