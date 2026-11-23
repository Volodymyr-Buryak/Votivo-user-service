package com.votivo.user_service.integration.keycloak;

import com.votivo.user_service.config.IdentityManagementProperties;
import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.ProfileConflictException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KeycloakUserManagementClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test/auth");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final IdentityProperties identity = new IdentityProperties("https://identity.test/auth", "test",
            "read-only-client", "read-only-secret", 10, Duration.ofMinutes(1));
    private final IdentityManagementProperties properties = new IdentityManagementProperties("management", "write-secret", true);
    private final KeycloakUserManagementClient client = new KeycloakUserManagementClient(builder.build(),
            identity, properties, Clock.systemUTC());
    private final UUID id = UUID.randomUUID();
    private final String userUrl = "https://identity.test/auth/admin/realms/test/users/" + id;

    @Test
    void profileUpdateChangesOnlyFirstAndLastName() {
        token("cached");
        server.expect(requestTo(userUrl)).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer cached"))
                .andExpect(content().json("""
                        {"firstName":"New","lastName":"Name"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withNoContent());
        client.updateProfile(id, "New", "Name");
        server.verify();
    }

    @Test
    void statusLogoutAndDeleteUseOnlyIntendedEndpointsAndReuseToken() {
        token("cached");
        server.expect(requestTo(userUrl)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"enabled\":false}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withNoContent());
        server.expect(requestTo(userUrl + "/logout")).andExpect(method(HttpMethod.POST)).andRespond(withNoContent());
        server.expect(requestTo(userUrl)).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        client.setEnabled(id, false);
        client.logout(id);
        client.delete(id);
        server.verify();
    }

    @Test
    void onlyExplicit401IsRetriedOnceWithAFreshManagementToken() {
        token("rejected");
        server.expect(requestTo(userUrl)).andExpect(header("Authorization", "Bearer rejected"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        token("fresh");
        server.expect(requestTo(userUrl)).andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withNoContent());
        client.delete(id);
        server.verify();
    }

    @Test
    void second401IsSanitizedAndInvalidatesRejectedTokenWithoutAnotherRetry() {
        token("rejected");
        server.expect(requestTo(userUrl)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        token("fresh");
        server.expect(requestTo(userUrl)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sensitive-response"));
        token("recovered");
        server.expect(requestTo(userUrl)).andExpect(header("Authorization", "Bearer recovered"))
                .andRespond(withNoContent());
        assertThatThrownBy(() -> client.delete(id)).isInstanceOf(IdentityAccessException.class)
                .hasMessage("Identity provider management request failed").hasNoCause();
        client.delete(id);
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"FORBIDDEN", "BAD_REQUEST", "INTERNAL_SERVER_ERROR", "SERVICE_UNAVAILABLE"})
    void upstreamFailuresAreSanitizedAndNotRetried(HttpStatus status) {
        token("cached");
        server.expect(requestTo(userUrl)).andRespond(withStatus(status).body("sensitive-response"));
        assertThatThrownBy(() -> client.delete(id)).isInstanceOf(IdentityAccessException.class)
                .hasMessage("Identity provider management request failed").hasNoCause();
        server.verify();
    }

    @Test
    void upstream404And409HaveDomainMeanings() {
        token("cached");
        server.expect(requestTo(userUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(userUrl)).andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> client.delete(id)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> client.updateProfile(id, "New", "Name"))
                .isInstanceOf(ProfileConflictException.class);
        server.verify();
    }

    @Test
    void ambiguousConnectionFailureIsNotRetriedOrExposed() {
        token("cached");
        server.expect(requestTo(userUrl)).andRespond(withException(new IOException("sensitive network address")));
        assertThatThrownBy(() -> client.delete(id)).isInstanceOf(IdentityAccessException.class)
                .hasMessage("Identity provider management request failed").hasNoCause();
        server.verify();
    }

    @Test
    void missingSecretFailsAtWriteTimeWithoutCallingUpstream() {
        var missingSecret = new KeycloakUserManagementClient(builder.build(), identity,
                new IdentityManagementProperties("management", null, true), Clock.systemUTC());
        assertThatThrownBy(() -> missingSecret.delete(id)).isInstanceOf(IdentityAccessException.class);
        assertThat(properties.toString()).doesNotContain("write-secret");
        server.verify();
    }

    private void token(String value) {
        server.expect(requestTo("https://identity.test/auth/realms/test/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("grant_type=client_credentials&client_id=management&client_secret=write-secret"))
                .andRespond(withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }
}
