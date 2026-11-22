package com.votivo.user_service.integration.keycloak;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.votivo.user_service.service.exception.IdentityAccessException;

@RequiredArgsConstructor
public final class KeycloakTokenProvider {
    // Lombok builds the constructor from the final fields in this order; the callers rely on it.
    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private CachedToken cachedToken;

    synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedToken.expiresAt())) {
            return cachedToken.value();
        }
        TokenResponse response = requestToken();
        long skew = Math.min(30, response.expiresIn() / 10);
        cachedToken = new CachedToken(response.accessToken(), now.plusSeconds(response.expiresIn() - skew));
        return cachedToken.value();
    }

    synchronized void invalidate(String rejectedToken) {
        if (cachedToken != null && cachedToken.value().equals(rejectedToken)) {
            cachedToken = null;
        }
    }

    private TokenResponse requestToken() {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new IdentityAccessException("Identity provider credentials are unavailable");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            TokenResponse response = http.post()
                    .uri(uri -> uri.pathSegment("realms", realm, "protocol", "openid-connect", "token").build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(TokenResponse.class);
            if (response == null || !StringUtils.hasText(response.accessToken()) || response.expiresIn() <= 0) {
                throw new IdentityAccessException("Identity provider returned an invalid token response");
            }
            return response;
        } catch (RestClientException exception) {
            throw new IdentityAccessException("Identity provider token request failed");
        }
    }

    private record CachedToken(
            String value,
            Instant expiresAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {}
}
