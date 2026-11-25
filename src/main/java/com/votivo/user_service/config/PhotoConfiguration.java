package com.votivo.user_service.config;

import com.votivo.user_service.image.validation.ImageValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhotoProperties.class)
public class PhotoConfiguration {
    @Bean
    ImageValidator imageValidator(PhotoProperties properties) {
        return new ImageValidator(properties);
    }
}
