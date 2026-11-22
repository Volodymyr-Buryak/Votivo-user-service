package com.votivo.user_service.integration.keycloak;

import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.IdentityUserNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KeycloakAdminClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test/auth");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KeycloakAdminClient client = new KeycloakAdminClient(builder.build(),
            new IdentityProperties("https://identity.test/auth", "test", "client", "secret", 2, Duration.ofMinutes(1)), Clock.systemUTC());

    private void token(String value) {
        server.expect(requestTo("https://identity.test/auth/realms/test/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("grant_type=client_credentials&client_id=client&client_secret=secret"))
                .andRespond(withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void retries401OnceAndReusesRefreshedTokenForCompositeRoles() {
        UUID id = UUID.randomUUID();
        String userUrl = "https://identity.test/auth/admin/realms/test/users/" + id;
        token("old");
        server.expect(requestTo(userUrl)).andExpect(header("Authorization", "Bearer old"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        token("fresh");
        server.expect(requestTo(userUrl)).andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"enabled\":true,\"unknown\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(userUrl + "/role-mappings/realm/composite"))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("[{\"name\":\"user\",\"composite\":true},{\"name\":\"admin\"}]", MediaType.APPLICATION_JSON));
        assertThat(client.findUser(id)).get().extracting(RemoteUser::id).isEqualTo(id);
        assertThat(client.realmRoles(id)).containsExactlyInAnyOrder("user", "admin");
        server.verify();
    }

    @Test
    void onlyUser404MeansAbsenceAndListing404Fails() {
        UUID id = UUID.randomUUID();
        token("cached");
        server.expect(requestTo("https://identity.test/auth/admin/realms/test/users/" + id))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("https://identity.test/auth/admin/realms/test/users?first=0&max=2"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.findUser(id)).isEmpty();
        assertThatThrownBy(() -> client.listUsers(0, 2)).isInstanceOf(IdentityAccessException.class);
        server.verify();
    }

    @Test
    void second401FailsWithoutAnotherRetryAndInvalidatesRejectedToken() {
        UUID id = UUID.randomUUID();
        String url = "https://identity.test/auth/admin/realms/test/users/" + id;
        token("old");
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        token("fresh");
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sensitive-response"));
        token("recovered");
        server.expect(requestTo(url)).andExpect(header("Authorization", "Bearer recovered"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findUser(id)).isInstanceOf(IdentityAccessException.class)
                .hasMessage("Identity provider request failed with HTTP 401").hasNoCause();
        assertThat(client.findUser(id)).get().extracting(RemoteUser::id).isEqualTo(id);
        server.verify();
    }

    @Test
    void successfulEmptyResponseIsFailureRatherThanDeletion() {
        UUID id = UUID.randomUUID();
        token("cached");
        server.expect(requestTo("https://identity.test/auth/admin/realms/test/users/" + id))
                .andRespond(withSuccess());
        assertThatThrownBy(() -> client.findUser(id)).isInstanceOf(IdentityAccessException.class);
        server.verify();
    }

    @Test
    void missingRolesCanBeDistinguishedFromListingFailure() {
        UUID id = UUID.randomUUID();
        token("cached");
        server.expect(requestTo("https://identity.test/auth/admin/realms/test/users/" + id
                        + "/role-mappings/realm/composite"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("https://identity.test/auth/admin/realms/test/users?first=0&max=2"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.realmRoles(id)).isInstanceOf(IdentityUserNotFoundException.class);
        assertThatThrownBy(() -> client.listUsers(0, 2)).isExactlyInstanceOf(IdentityAccessException.class);
        server.verify();
    }
}
