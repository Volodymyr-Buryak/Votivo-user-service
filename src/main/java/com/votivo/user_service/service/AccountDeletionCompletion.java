package com.votivo.user_service.service;

import com.votivo.user_service.integration.keycloak.KeycloakUserManagementClient;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.ProfileConflictException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable retries: disabling and session revocation are idempotent; no database transaction spans HTTP calls. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionCompletion {
    private final KeycloakUserManagementClient management;
    private final ProfileWriter writer;

    public boolean complete(UUID id) {
        try {
            management.setEnabled(id, false);
            management.logout(id);
            management.anonymizeDeletedUser(id);
        } catch (UserNotFoundException absent) {
            // An independently removed identity cannot log in and has no sessions left.
        } catch (IdentityAccessException | ProfileConflictException failure) {
            log.warn("Identity deactivation and identifier release are pending for deleted user {}", id);
            return false;
        }
        writer.completeIdentityDeletion(id);
        return true;
    }

    @Scheduled(fixedDelayString = "${app.account-deletion.retry-interval:PT30S}",
            initialDelayString = "${app.account-deletion.retry-interval:PT30S}")
    public void retryPending() {
        for (UUID id : writer.pendingIdentityDeletions()) {
            try { complete(id); }
            catch (RuntimeException failure) { log.warn("Account deletion completion will retry for {}", id); }
        }
    }
}
