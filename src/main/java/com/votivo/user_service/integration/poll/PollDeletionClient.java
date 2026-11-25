package com.votivo.user_service.integration.poll;

import com.votivo.user_service.service.exception.PollServiceUnavailableException;
import com.votivo.user_service.service.exception.UnfinishedPollsException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class PollDeletionClient {
    private final RestClient http;
    private final String secret;

    @org.springframework.beans.factory.annotation.Autowired
    public PollDeletionClient(RestClient.Builder builder,
            @Value("${app.account-deletion.poll-url}") String baseUrl,
            @Value("${app.account-deletion.secret:}") String secret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.secret = secret;
    }

    PollDeletionClient(RestClient http, String secret) {
        this.http = http;
        this.secret = secret;
    }

    public void reserve(UUID id) { request(HttpMethod.PUT, id); }
    public void release(UUID id) { request(HttpMethod.DELETE, id); }

    private void request(HttpMethod method, UUID id) {
        if (secret == null || secret.isBlank()) throw new PollServiceUnavailableException();
        try {
            http.method(method).uri("/internal/v1/users/{id}/deletion", id)
                    .header("X-Account-Deletion-Secret", secret).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) throw new UnfinishedPollsException();
            throw new PollServiceUnavailableException();
        } catch (RestClientException exception) {
            throw new PollServiceUnavailableException();
        }
    }
}
