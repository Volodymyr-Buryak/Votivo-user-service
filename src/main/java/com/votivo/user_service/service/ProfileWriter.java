package com.votivo.user_service.service;

import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.entity.UserRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProfileWriter {
    void upsert(RemoteUser source, Set<UserRole> roles);
    void softDelete(UUID id);
    void setEnabled(UUID id, boolean enabled);
    void requestDeletion(UUID id);
    void completeIdentityDeletion(UUID id);
    List<UUID> pendingIdentityDeletions();
    List<UUID> activeIds();
}
