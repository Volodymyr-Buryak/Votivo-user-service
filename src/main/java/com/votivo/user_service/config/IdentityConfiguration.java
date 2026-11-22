package com.votivo.user_service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IdentityProperties.class, IdentityManagementProperties.class})
class IdentityConfiguration {
}
