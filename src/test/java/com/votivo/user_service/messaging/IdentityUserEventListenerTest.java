package com.votivo.user_service.messaging;

import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.service.UserSynchronizationService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class IdentityUserEventListenerTest {
    private static final UUID USER = UUID.fromString("f22b2331-1c79-44c2-9bae-4a3eb71154e8");
    private UserSynchronizationService synchronization;
    private IdentityReconciliation reconciliation;
    private IdentityUserEventListener listener;

    @BeforeEach
    void setUp() {
        synchronization = mock(UserSynchronizationService.class);
        reconciliation = mock(IdentityReconciliation.class);
        listener = new IdentityUserEventListener(synchronization, reconciliation, JsonMapper.builder().build(),
                new IdentityProperties("http://identity", "votivo.test", "sync", "secret", 100, Duration.ofHours(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGISTER", "UPDATE_PROFILE", "UPDATE_EMAIL", "VERIFY_EMAIL", "DELETE_ACCOUNT"})
    void profileEventsFetchCurrentProfileIgnoringProviderTypeHeader(String type) {
        listener.onMessage(message("KK.EVENT.CLIENT.votivotest.SUCCESS.web." + type,
                "{\"type\":\"" + type + "\",\"userId\":\"" + USER + "\",\"unknown\":true}"));
        verify(synchronization).synchronize(USER);
        verifyNoInteractions(reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "REFRESH_TOKEN", "LOGOUT"})
    void authenticationNoiseDoesNotEvenParseBody(String event) {
        listener.onMessage(message("KK.EVENT.CLIENT.votivotest.SUCCESS.web." + event, "not-json"));
        verifyNoInteractions(synchronization, reconciliation);
    }

    @Test
    void unrelatedRealmAndFailedEventsAreIgnored() {
        listener.onMessage(message("KK.EVENT.CLIENT.other.SUCCESS.web.REGISTER", "not-json"));
        listener.onMessage(message("KK.EVENT.ADMIN.votivotest.ERROR.USER.DELETE", "not-json"));
        verifyNoInteractions(synchronization, reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/role-mappings/realm", "/groups/a-group"})
    void adminDeleteReReadsUserIncludingMappingAndMembershipDeletes(String suffix) {
        listener.onMessage(message("KK.EVENT.ADMIN.votivotest.SUCCESS.USER.DELETE",
                "{\"operationType\":\"DELETE\",\"resourcePath\":\"users/" + USER + suffix + "\"}"));
        verify(synchronization).synchronize(USER);
        verifyNoInteractions(reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "DELETE"})
    void directRealmRoleAssignmentsAlwaysReReadCurrentUser(String operation) {
        listener.onMessage(message("KK.EVENT.ADMIN.votivotest.SUCCESS.REALM_ROLE_MAPPING." + operation,
                "{\"operationType\":\"" + operation + "\",\"resourcePath\":\"users/" + USER + "/role-mappings/realm\"}"));
        verify(synchronization).synchronize(USER);
        verifyNoInteractions(reconciliation);
    }

    @Test
    void directClientRoleAssignmentReReadsCurrentUser() {
        listener.onMessage(message("KK.EVENT.ADMIN.votivotest.SUCCESS.CLIENT_ROLE_MAPPING.DELETE",
                "{\"operationType\":\"DELETE\",\"resourcePath\":\"users/" + USER + "/role-mappings/clients/client-id\"}"));
        verify(synchronization).synchronize(USER);
        verifyNoInteractions(reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE", "REALM_ROLE", "CLIENT_ROLE", "GROUP", "GROUP_MEMBERSHIP",
            "REALM_ROLE_MAPPING", "CLIENT_ROLE_MAPPING", "REALM_ROLE_COMPOSITE", "CLIENT_ROLE_COMPOSITE"})
    void globalAuthorizationChangesReconcileAllProfiles(String resource) {
        listener.onMessage(message("KK.EVENT.ADMIN.votivotest.SUCCESS." + resource + ".UPDATE",
                "{\"operationType\":\"UPDATE\",\"resourcePath\":\"groups/group-id\"}"));
        verify(reconciliation).reconcile();
        verifyNoInteractions(synchronization);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{}", "{\"type\":\"REGISTER\",\"userId\":\"bad\"}",
            "{\"type\":\"REGISTER\",\"userId\":\"1-1-1-1-1\"}",
            "{\"type\":\"LOGIN\",\"userId\":\"f22b2331-1c79-44c2-9bae-4a3eb71154e8\"}"})
    void invalidRelevantPayloadIsRejectedForBoundedRetry(String body) {
        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(message("KK.EVENT.CLIENT.votivotest.SUCCESS.web.REGISTER", body)));
        verifyNoInteractions(synchronization, reconciliation);
    }

    @Test
    void malformedAdminUserPathDoesNotBecomeGlobalReconciliation() {
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(message(
                "KK.EVENT.ADMIN.votivotest.SUCCESS.USER.UPDATE",
                "{\"operationType\":\"UPDATE\",\"resourcePath\":\"users/not-a-uuid/role-mappings\"}")));
        verifyNoInteractions(synchronization, reconciliation);
    }

    @Test
    void serviceFailurePropagatesToBrokerRetry() {
        doThrow(new IllegalStateException("unavailable")).when(synchronization).synchronize(USER);
        assertThrows(IllegalStateException.class, () -> listener.onMessage(message(
                "KK.EVENT.CLIENT.votivotest.SUCCESS.web.REGISTER",
                "{\"type\":\"REGISTER\",\"userId\":\"" + USER + "\"}")));
    }

    private static Message message(String routingKey, String body) {
        var properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        properties.setHeader("__TypeId__", "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
