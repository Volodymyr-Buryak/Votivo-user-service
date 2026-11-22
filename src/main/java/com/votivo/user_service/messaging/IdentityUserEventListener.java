package com.votivo.user_service.messaging;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.config.RabbitConfiguration;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import com.votivo.user_service.service.UserSynchronizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "app.identity", name = "sync-enabled", havingValue = "true", matchIfMissing = true)
public class IdentityUserEventListener {
    private static final Set<String> PROFILE_EVENTS = Set.of(
            "REGISTER", "UPDATE_PROFILE", "UPDATE_EMAIL", "VERIFY_EMAIL", "DELETE_ACCOUNT"
    );

    private static final Set<String> GLOBAL_RESOURCES = Set.of(
            "ROLE", "REALM_ROLE", "CLIENT_ROLE", "GROUP", "REALM_ROLE_MAPPING", "CLIENT_ROLE_MAPPING",
            "REALM_ROLE_COMPOSITE", "CLIENT_ROLE_COMPOSITE", "GROUP_MEMBERSHIP"
    );

    private final JsonMapper json;
    private final String adminPrefix;
    private final String clientPrefix;
    private final IdentityReconciliation reconciliation;
    private final UserSynchronizationService synchronization;

    public IdentityUserEventListener(UserSynchronizationService synchronization, IdentityReconciliation reconciliation,
                                     JsonMapper json, IdentityProperties properties) {
        this.synchronization = synchronization;
        this.reconciliation = reconciliation;
        this.json = json;
        this.clientPrefix = RabbitConfiguration.clientPrefix(properties.realm());
        this.adminPrefix = RabbitConfiguration.adminPrefix(properties.realm());
    }

    @RabbitListener(queues = RabbitConfiguration.QUEUE)
    public void onMessage(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (routingKey == null) {
            return;
        }
        if (routingKey.startsWith(clientPrefix)) {
            handleClient(message, routingKey);
        } else if (routingKey.startsWith(adminPrefix)) {
            handleAdmin(message, routingKey.substring(adminPrefix.length()));
        }
    }

    private void handleClient(Message message, String routingKey) {
        String event = routingKey.substring(routingKey.lastIndexOf('.') + 1);
        if (!PROFILE_EVENTS.contains(event)) {
            return;
        }
        JsonNode payload = payload(message);
        if (!event.equals(text(payload, "type"))) {
            throw invalidPayload();
        }
        synchronization.synchronize(userId(text(payload, "userId")));
    }

    private void handleAdmin(Message message, String routingSuffix) {
        String[] routingParts = routingSuffix.split("\\.");
        if (routingParts.length != 2) {
            throw invalidPayload();
        }

        String resourceType = routingParts[0];
        if (!resourceType.startsWith("USER") && !GLOBAL_RESOURCES.contains(resourceType)) {
            return;
        }

        JsonNode payload = payload(message);
        if (!routingParts[1].equals(text(payload, "operationType"))) {
            throw invalidPayload();
        }

        String path = text(payload, "resourcePath");
        if (path.startsWith("users/")) {
            String[] segments = path.split("/", -1);
            synchronization.synchronize(userId(segments[1]));
        } else if (GLOBAL_RESOURCES.contains(resourceType)) {
            reconciliation.reconcile();
        } else {
            throw invalidPayload();
        }
    }

    private JsonNode payload(Message message) {
        JsonNode payload;
        try {
            payload = json.readTree(message.getBody());
        } catch (JacksonException exception) {
            throw invalidPayload();
        }

        if (payload == null || !payload.isObject()) {
            throw invalidPayload();
        }

        return payload;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw invalidPayload();
        }
        return value.asString();
    }

    private static UUID userId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw invalidPayload();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalidPayload();
        }
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException("Invalid identity synchronization event");
    }
}
