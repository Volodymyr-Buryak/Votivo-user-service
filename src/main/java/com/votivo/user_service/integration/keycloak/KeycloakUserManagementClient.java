package com.votivo.user_service.integration.keycloak;

import com.votivo.user_service.config.IdentityManagementProperties;
import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.ProfileConflictException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Uses a separate service account with view-users/manage-users, never realm-admin. */
@Component
public class KeycloakUserManagementClient {
    private final RestClient http;
    private final IdentityProperties identity;
    private final KeycloakTokenProvider tokens;

    @Autowired
    public KeycloakUserManagementClient(RestClient.Builder builder, IdentityProperties identity,
                                        IdentityManagementProperties properties) {
        this(KeycloakHttpClient.build(builder, identity.baseUrl()), identity, properties, Clock.systemUTC());
    }

    KeycloakUserManagementClient(RestClient http, IdentityProperties identity,
                                 IdentityManagementProperties properties, Clock clock) {
        this.http = http;
        this.identity = identity;
        this.tokens = new KeycloakTokenProvider(http, identity.realm(), properties.clientId(),
                properties.clientSecret(), clock);
    }

    public void updateProfile(UUID id, String firstName, String lastName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        write(HttpMethod.PUT, id, null, body);
    }

    public void setEnabled(UUID id, boolean enabled) {
        write(HttpMethod.PUT, id, null, Map.of("enabled", enabled));
    }

    public void logout(UUID id) {
        write(HttpMethod.POST, id, "logout", null);
    }

    public void delete(UUID id) {
        write(HttpMethod.DELETE, id, null, null);
    }

    private void write(HttpMethod method, UUID id, String action, Object body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = tokens.accessToken();
            try {
                sendWrite(method, id, action, body, token);
                return;
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                // A rejected authentication did not execute the write. Never retry ambiguous failures.
                if (status == 401) {
                    tokens.invalidate(token);
                    if (attempt == 0) continue;
                }
                if (status == 404) throw new UserNotFoundException();
                if (status == 409) throw new ProfileConflictException();
                throw new IdentityAccessException("Identity provider management request failed");
            } catch (RestClientException exception) {
                throw new IdentityAccessException("Identity provider management request failed");
            }
        }
        throw new IdentityAccessException("Identity provider management authentication failed");
    }

    private void sendWrite(HttpMethod method, UUID id, String action, Object body, String token) {
        var request = http.method(method).uri(uri -> {
            uri.pathSegment("admin", "realms", identity.realm(), "users", id.toString());
            if (action != null) uri.pathSegment(action);
            return uri.build();
        }).headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        request.retrieve().toBodilessEntity();
    }
}
