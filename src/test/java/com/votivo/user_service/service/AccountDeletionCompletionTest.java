package com.votivo.user_service.service;

import com.votivo.user_service.integration.keycloak.KeycloakUserManagementClient;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionCompletionTest {
    private final KeycloakUserManagementClient management = mock(KeycloakUserManagementClient.class);
    private final ProfileWriter writer = mock(ProfileWriter.class);
    private final AccountDeletionCompletion service = new AccountDeletionCompletion(management, writer);
    private final UUID id = UUID.randomUUID();

    @Test
    void disablesAndRevokesSessionsWithoutPhysicallyDeletingIdentity() {
        assertThat(service.complete(id)).isTrue();
        var order = inOrder(management, writer);
        order.verify(management).setEnabled(id, false);
        order.verify(management).logout(id);
        order.verify(management).anonymizeDeletedUser(id);
        order.verify(writer).completeIdentityDeletion(id);
        verify(management, never()).delete(any());
    }

    @Test
    void retriesAfterAnIdentityOutageAndOnlyClearsMarkerAfterSessionRevocation() {
        doThrow(new IdentityAccessException("offline")).doNothing().when(management).logout(id);
        assertThat(service.complete(id)).isFalse();
        verify(writer, never()).completeIdentityDeletion(any());
        when(writer.pendingIdentityDeletions()).thenReturn(List.of(id));
        service.retryPending();
        verify(writer).completeIdentityDeletion(id);
        verify(management, times(2)).setEnabled(id, false);
    }

    @Test
    void failedIdentifierReleaseKeepsTheDurableMarkerAndRetries() {
        doThrow(new IdentityAccessException("offline")).doNothing().when(management).anonymizeDeletedUser(id);
        assertThat(service.complete(id)).isFalse();
        verify(writer, never()).completeIdentityDeletion(any());
        when(writer.pendingIdentityDeletions()).thenReturn(List.of(id));
        service.retryPending();
        verify(management, times(2)).anonymizeDeletedUser(id);
        verify(writer).completeIdentityDeletion(id);
    }

    @Test
    void alreadyAbsentIdentityCompletesDeletion() {
        doThrow(new UserNotFoundException()).when(management).setEnabled(id, false);
        assertThat(service.complete(id)).isTrue();
        verify(writer).completeIdentityDeletion(id);
    }
}
