package com.votivo.user_service.service.impl;

import com.votivo.user_service.dto.request.UpdateProfileRequest;
import com.votivo.user_service.dto.response.UserPageResponse;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.integration.keycloak.KeycloakUserManagementClient;
import com.votivo.user_service.service.ProfileReader;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.service.ProfileWriter;
import com.votivo.user_service.service.UserSynchronizationService;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import com.votivo.user_service.service.support.UserLockManager;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Orchestrates remote identity writes outside the short projection transactions. */
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {
    private final ProfileReader reader;
    private final UserSynchronizationService synchronization;
    private final KeycloakUserManagementClient management;
    private final ProfileWriter writer;
    private final UserLockManager locks;

    @Override
    public UserResponse getOwn(UUID id) {
        UserResponse user = getById(id);
        if (!user.enabled()) throw new ProfileAccessDeniedException();
        return user;
    }

    @Override
    public UserResponse getById(UUID id) {
        var existing = reader.find(id);
        if (existing.isPresent()) return existing.get();
        if (reader.exists(id)) throw new UserNotFoundException();
        synchronization.synchronize(id);
        return required(id);
    }

    @Override
    public UserResponse updateOwn(UUID id, UpdateProfileRequest request) {
        return locks.withUserLock(id, () -> updateOwnLocked(id, request));
    }

    private UserResponse updateOwnLocked(UUID id, UpdateProfileRequest request) {
        UserResponse current = getOwn(id);
        boolean emailChanged = !Objects.equals(current.email(), request.email());
        management.updateProfile(id, request.firstName(), request.lastName(), request.email(), emailChanged);
        synchronization.synchronize(id);
        UserResponse updated = required(id);
        if (emailChanged || !updated.emailVerified()) management.sendVerification(id);
        return updated;
    }

    @Override
    public void deleteOwn(UUID id) {
        locks.withUserLock(id, () -> {
            getOwn(id);
            management.delete(id);
            writer.softDelete(id);
            return null;
        });
    }

    @Override
    public UserPageResponse list(String search, Boolean enabled, int page, int size) {
        return reader.list(search, enabled, page, size);
    }

    @Override
    public UserResponse setEnabled(UUID actorId, UUID targetId, boolean enabled) {
        getOwn(actorId);
        if (!enabled && actorId.equals(targetId)) throw new ProfileAccessDeniedException();
        return locks.withUserLock(targetId, () -> setEnabledLocked(targetId, enabled));
    }

    private UserResponse setEnabledLocked(UUID targetId, boolean enabled) {
        getById(targetId);
        management.setEnabled(targetId, enabled);
        synchronizeAndRevokeSessions(targetId, enabled);
        return required(targetId);
    }

    private void synchronizeAndRevokeSessions(UUID targetId, boolean enabled) {
        try {
            synchronization.synchronize(targetId);
        } catch (RuntimeException failure) {
            if (!enabled) {
                try {
                    management.logout(targetId);
                } catch (RuntimeException logoutFailure) {
                    failure.addSuppressed(logoutFailure);
                }
            }
            throw failure;
        }
        if (!enabled) management.logout(targetId);
    }

    private UserResponse required(UUID id) {
        return reader.find(id).orElseThrow(UserNotFoundException::new);
    }
}
