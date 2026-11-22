package com.votivo.user_service.service.impl;

import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.integration.keycloak.KeycloakAdminClient;
import com.votivo.user_service.service.ProfileWriter;
import com.votivo.user_service.service.UserSynchronizationService;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.IdentityUserNotFoundException;
import com.votivo.user_service.service.support.UserLockManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserSynchronizationServiceImpl implements UserSynchronizationService {
    private final KeycloakAdminClient keycloak;
    private final ProfileWriter writer;
    private final int pageSize;
    private final UserLockManager locks;

    public UserSynchronizationServiceImpl(KeycloakAdminClient keycloak, ProfileWriter writer, IdentityProperties properties,
                                          UserLockManager locks) {
        this.keycloak = keycloak;
        this.writer = writer;
        this.locks = locks;
        this.pageSize = properties.pageSize();
        if (pageSize <= 0) throw new IllegalArgumentException("identity.page-size must be positive");
    }

    @Override
    public void synchronize(UUID id) {
        locks.withUserLock(id, () -> {
            synchronizeLocked(id);
            return null;
        });
    }

    private void synchronizeLocked(UUID id) {
        var remote = keycloak.findUser(id);
        if (remote.isEmpty()) {
            writer.softDelete(id);
            return;
        }
        RemoteUser source = remote.get();
        if (!id.equals(source.id())) {
            throw new IdentityAccessException("Identity provider returned an incomplete or mismatched user");
        }
        if (source.isServiceAccount()) return;
        if (source.enabled() == null || source.emailVerified() == null) {
            throw new IdentityAccessException("Identity provider returned an incomplete or mismatched user");
        }
        Set<String> realmRoles;
        try {
            realmRoles = keycloak.realmRoles(id);
        } catch (IdentityUserNotFoundException failure) {
            // A deletion may occur between the user and roles reads; confirm it per ID.
            if (keycloak.findUser(id).isPresent()) throw failure;
            writer.softDelete(id);
            return;
        }
        writer.upsert(source, profileRoles(realmRoles));
    }

    private Set<UserRole> profileRoles(Set<String> realmRoles) {
        Set<UserRole> roles = new HashSet<>();
        for (UserRole role : UserRole.values()) {
            if (realmRoles.contains(role.name())) roles.add(role);
        }
        return roles;
    }

    @Override
    public void reconcile() {
        Set<UUID> observed = new HashSet<>();
        int first = 0;
        while (true) {
            List<RemoteUser> page = keycloak.listUsers(first, pageSize);
            for (RemoteUser source : page) {
                if (source.id() == null) throw new IllegalStateException("Identity listing returned a user without an ID");
                observed.add(source.id());
                if (!source.isServiceAccount()) synchronize(source.id());
            }
            if (page.size() < pageSize) break;
            first = Math.addExact(first, pageSize);
        }
        // Listing is only a candidate filter. A current per-ID read must confirm every deletion.
        for (UUID id : writer.activeIds()) if (!observed.contains(id)) synchronize(id);
    }

}
