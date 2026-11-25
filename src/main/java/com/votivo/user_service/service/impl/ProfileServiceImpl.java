package com.votivo.user_service.service.impl;

import com.votivo.user_service.integration.poll.PollDeletionClient;
import com.votivo.user_service.service.AccountDeletionCompletion;

import com.votivo.user_service.dto.request.UpdateProfileRequest;
import com.votivo.user_service.dto.response.UserPageResponse;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.dto.response.UserHeaderResponse;
import com.votivo.user_service.integration.keycloak.KeycloakUserManagementClient;
import com.votivo.user_service.service.ProfileReader;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.service.ProfileWriter;
import com.votivo.user_service.service.UserSynchronizationService;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import com.votivo.user_service.service.support.UserLockManager;
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
    private final PollDeletionClient polls;
    private final AccountDeletionCompletion deletions;

    @Override
    public UserResponse getOwn(UUID id) {
        UserResponse user = getById(id);
        if (!user.enabled()) throw new ProfileAccessDeniedException();
        return user;
    }

    @Override
    public UserHeaderResponse getOwnHeader(UUID id) {
        UserResponse user = getOwn(id);
        return new UserHeaderResponse(user.firstName(), user.lastName());
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
        getOwn(id);
        management.updateProfile(id, request.firstName(), request.lastName());
        synchronization.synchronize(id);
        UserResponse updated = required(id);
        return updated;
    }

    @Override
    public boolean deleteOwn(UUID id) {
        return locks.withUserLock(id, () -> {
            if (getOwn(id).roles().contains(com.votivo.user_service.entity.UserRole.admin))
                throw new ProfileAccessDeniedException();
            // Recheck authoritative status and roles before the irreversible local change.
            synchronization.synchronize(id);
            if (getOwn(id).roles().contains(com.votivo.user_service.entity.UserRole.admin))
                throw new ProfileAccessDeniedException();
            polls.reserve(id);
            try {
                writer.requestDeletion(id);
            } catch (RuntimeException failure) {
                try {
                    // A second instance or an ambiguous commit may already have deleted the profile.
                    // Never reopen publishing for a confirmed tombstone.
                    if (reader.find(id).isPresent() || !reader.exists(id)) polls.release(id);
                    else return false;
                } catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
                throw failure;
            }
            try { return deletions.complete(id); }
            catch (RuntimeException failure) { return false; } // Durable marker is retried after restart.
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
            // Commit confirmed Keycloak status before optional profile/role reconciliation.
            writer.setEnabled(targetId, enabled);
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
