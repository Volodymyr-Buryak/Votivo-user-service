package com.votivo.user_service.integration.poll;

import com.votivo.user_service.service.exception.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PollDeletionClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://poll.test");
    private final UUID id = UUID.randomUUID();

    @Test
    void reservesAndReleasesUsingDedicatedCredentialAndNoBrowserToken() {
        var server = MockRestServiceServer.bindTo(builder).build();
        var actual = new PollDeletionClient(builder.build(), "internal-only");
        server.expect(requestTo("http://poll.test/internal/v1/users/" + id + "/deletion"))
                .andExpect(method(HttpMethod.PUT)).andExpect(header("X-Account-Deletion-Secret", "internal-only"))
                .andExpect(headerDoesNotExist("Authorization")).andRespond(withNoContent());
        server.expect(requestTo("http://poll.test/internal/v1/users/" + id + "/deletion"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        actual.reserve(id); actual.release(id); server.verify();
    }

    @Test
    void mapsOpenPollsAndUnavailableUpstreamAndFailsClosedWithoutSecret() {
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new PollDeletionClient(builder.build(), "secret");
        server.expect(anything()).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.reserve(id)).isInstanceOf(UnfinishedPollsException.class);
        assertThatThrownBy(() -> client.reserve(id)).isInstanceOf(PollServiceUnavailableException.class);
        assertThatThrownBy(() -> new PollDeletionClient(builder.build(), "").reserve(id))
                .isInstanceOf(PollServiceUnavailableException.class);
        server.verify();
    }
}
