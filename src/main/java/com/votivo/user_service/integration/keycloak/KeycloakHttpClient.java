package com.votivo.user_service.integration.keycloak;

import java.time.Duration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

public final class KeycloakHttpClient {
    private KeycloakHttpClient() {}

    static RestClient build(RestClient.Builder builder, String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

}
