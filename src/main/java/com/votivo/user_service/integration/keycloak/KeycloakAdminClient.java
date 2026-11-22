package com.votivo.user_service.integration.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.IdentityUserNotFoundException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

@Component
public class KeycloakAdminClient {
    private final RestClient http;
    private final IdentityProperties properties;
    private final KeycloakTokenProvider tokens;

    @Autowired
    public KeycloakAdminClient(RestClient.Builder builder, IdentityProperties properties) {
        this(KeycloakHttpClient.build(builder, properties.baseUrl()), properties, Clock.systemUTC());
    }

    KeycloakAdminClient(RestClient http, IdentityProperties properties, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.tokens = new KeycloakTokenProvider(http, properties.realm(), properties.clientId(),
                properties.clientSecret(), clock);
    }

    public List<RemoteUser> listUsers(int first, int max) {
        if (first < 0 || max <= 0) throw new IllegalArgumentException("Invalid identity page bounds");
        return get(uri -> uri.pathSegment("admin", "realms", properties.realm(), "users")
                        .queryParam("first", first).queryParam("max", max).build(),
                new ParameterizedTypeReference<List<RemoteUser>>() {}, false).orElseThrow();
    }

    public Optional<RemoteUser> findUser(UUID id) {
        return get(uri -> uri.pathSegment("admin", "realms", properties.realm(), "users", id.toString()).build(),
                new ParameterizedTypeReference<RemoteUser>() {}, true);
    }

    public Set<String> realmRoles(UUID id) {
        List<RemoteRole> roles = get(uri -> uri.pathSegment("admin", "realms", properties.realm(), "users",
                        id.toString(), "role-mappings", "realm", "composite").build(),
                new ParameterizedTypeReference<List<RemoteRole>>() {}, true)
                .orElseThrow(IdentityUserNotFoundException::new);
        return roles.stream().map(RemoteRole::name).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private <T> Optional<T> get(Function<UriBuilder, URI> uri, ParameterizedTypeReference<T> type, boolean absentOn404) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = tokens.accessToken();
            try {
                T body = http.get().uri(uri).headers(headers -> headers.setBearerAuth(token)).retrieve().body(type);
                if (body == null) throw new IdentityAccessException("Identity provider returned an empty response");
                return Optional.of(body);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (status == 401) {
                    tokens.invalidate(token);
                    if (attempt == 0) continue;
                }
                if (status == 404 && absentOn404) return Optional.empty();
                throw new IdentityAccessException("Identity provider request failed with HTTP " + status);
            } catch (RestClientException exception) {
                throw new IdentityAccessException("Identity provider request failed");
            }
        }
        throw new IdentityAccessException("Identity provider authentication failed");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RemoteRole(
            String name
    ) {}

}
