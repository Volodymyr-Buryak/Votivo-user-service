package com.votivo.user_service.dto.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import lombok.Builder;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteUser(
        UUID id,
        String email,
        String firstName,
        String lastName,
        Boolean emailVerified,
        Boolean enabled,
        Long createdTimestamp,
        String serviceAccountClientId
) {
    public boolean isServiceAccount() { return serviceAccountClientId != null; }
}
