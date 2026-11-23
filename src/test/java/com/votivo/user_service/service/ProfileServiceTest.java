package com.votivo.user_service.service;

import com.votivo.user_service.dto.request.UpdateProfileRequest;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.dto.response.UserHeaderResponse;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.integration.keycloak.KeycloakUserManagementClient;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import com.votivo.user_service.service.impl.ProfileServiceImpl;
import com.votivo.user_service.service.support.UserLockManager;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileServiceTest {
    private final ProfileReader reader = mock(ProfileReader.class);
    private final UserSynchronizationService synchronization = mock(UserSynchronizationService.class);
    private final KeycloakUserManagementClient management = mock(KeycloakUserManagementClient.class);
    private final ProfileWriter writer = mock(ProfileWriter.class);
    private final ProfileService service = new ProfileServiceImpl(reader, synchronization, management, writer, new UserLockManager());
    private final UUID id = UUID.randomUUID();

    @Test
    void existingProfileReadDoesNotContactIdentityProvider() {
        UserResponse current = response(id, true, "old@example.org");
        when(reader.find(id)).thenReturn(Optional.of(current));
        assertThat(service.getOwn(id)).isSameAs(current);
        verifyNoInteractions(synchronization, management, writer);
    }

    @Test
    void ownHeaderReturnsOnlyNamesWithoutContactingIdentityProvider() {
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "private@example.org")));
        assertThat(service.getOwnHeader(id)).isEqualTo(new UserHeaderResponse("Old", "Name"));
        verifyNoInteractions(synchronization, management, writer);
    }

    @Test
    void missingFirstLoginSynchronizesAndThenReadsProjection() {
        UserResponse current = response(id, true, "old@example.org");
        when(reader.find(id)).thenReturn(Optional.empty(), Optional.of(current));
        assertThat(service.getOwn(id)).isSameAs(current);
        var order = inOrder(reader, synchronization);
        order.verify(reader).find(id);
        order.verify(reader).exists(id);
        order.verify(synchronization).synchronize(id);
        order.verify(reader).find(id);
    }

    @Test
    void missingUpstreamUserAndDeletedProjectionAreNotFound() {
        when(reader.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(UserNotFoundException.class);
        verify(synchronization).synchronize(id);
        clearInvocations(synchronization);
        when(reader.exists(id)).thenReturn(true);
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(synchronization);
    }

    @Test
    void disabledOwnProfileCannotReadUpdateOrDelete() {
        when(reader.find(id)).thenReturn(Optional.of(response(id, false, "old@example.org")));
        assertThatThrownBy(() -> service.getOwn(id)).isInstanceOf(ProfileAccessDeniedException.class);
        assertThatThrownBy(() -> service.getOwnHeader(id)).isInstanceOf(ProfileAccessDeniedException.class);
        assertThatThrownBy(() -> service.updateOwn(id, new UpdateProfileRequest("New", "Name")))
                .isInstanceOf(ProfileAccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteOwn(id)).isInstanceOf(ProfileAccessDeniedException.class);
        verifyNoInteractions(management, writer);
        verify(synchronization, never()).synchronize(any());
    }

    @Test
    void nameUpdateWritesIdentityBeforeSyncWithoutSendingEmailVerification() {
        UserResponse current = response(id, true, "old@example.org");
        UserResponse updated = response(id, true, "old@example.org");
        when(reader.find(id)).thenReturn(Optional.of(current), Optional.of(updated));
        assertThat(service.updateOwn(id, new UpdateProfileRequest("New", "Name"))).isEqualTo(updated);
        var order = inOrder(management, synchronization, reader);
        order.verify(reader).find(id);
        order.verify(management).updateProfile(id, "New", "Name");
        order.verify(synchronization).synchronize(id);
        order.verify(reader).find(id);
        verifyNoMoreInteractions(management);
        verifyNoInteractions(writer);
    }

    @Test
    void projectionFailureDoesNotRetrySuccessfulIdentityWrite() {
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "old@example.org")));
        doThrow(new IdentityAccessException("Unavailable")).when(synchronization).synchronize(id);
        assertThatThrownBy(() -> service.updateOwn(id, new UpdateProfileRequest("New", "Name")))
                .isInstanceOf(IdentityAccessException.class);
        verify(management, times(1)).updateProfile(id, "New", "Name");
        verifyNoMoreInteractions(management);
    }

    @Test
    void deletionClearsProjectionOnlyAfterConfirmedIdentityDeletion() {
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "old@example.org")));
        service.deleteOwn(id);
        var order = inOrder(management, writer);
        order.verify(management).delete(id);
        order.verify(writer).softDelete(id);
        clearInvocations(writer);
        doThrow(new IdentityAccessException("Unavailable")).when(management).delete(id);
        assertThatThrownBy(() -> service.deleteOwn(id)).isInstanceOf(IdentityAccessException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void administratorCannotDisableSelf() {
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "admin@example.org")));
        assertThatThrownBy(() -> service.setEnabled(id, id, false)).isInstanceOf(ProfileAccessDeniedException.class);
        verifyNoInteractions(management, synchronization, writer);
    }

    @Test
    void disablingUserSynchronizesBeforeRevokingSessionsAndEnablingDoesNotLogout() {
        UUID target = UUID.randomUUID();
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "admin@example.org")));
        when(reader.find(target)).thenReturn(Optional.of(response(target, true, "user@example.org")),
                Optional.of(response(target, false, "user@example.org")));
        assertThat(service.setEnabled(id, target, false).enabled()).isFalse();
        var order = inOrder(management, synchronization);
        order.verify(management).setEnabled(target, false);
        order.verify(synchronization).synchronize(target);
        order.verify(management).logout(target);
        clearInvocations(management);
        service.setEnabled(id, target, true);
        verify(management).setEnabled(target, true);
        verify(management, never()).logout(any());
    }

    @Test
    void successfulBlockStillRevokesSessionsWhenProjectionRefreshFails() {
        UUID target = UUID.randomUUID();
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "admin@example.org")));
        when(reader.find(target)).thenReturn(Optional.of(response(target, true, "user@example.org")));
        doThrow(new IdentityAccessException("Projection refresh unavailable")).when(synchronization).synchronize(target);
        assertThatThrownBy(() -> service.setEnabled(id, target, false)).isInstanceOf(IdentityAccessException.class);
        verify(management).setEnabled(target, false);
        verify(management).logout(target);
    }

    @Test
    void failedLogoutDoesNotHideProjectionFailureDuringBlock() {
        UUID target = UUID.randomUUID();
        when(reader.find(id)).thenReturn(Optional.of(response(id, true, "admin@example.org")));
        when(reader.find(target)).thenReturn(Optional.of(response(target, true, "user@example.org")));
        var primary = new IdentityAccessException("Projection refresh unavailable");
        var secondary = new IdentityAccessException("Logout unavailable");
        doThrow(primary).when(synchronization).synchronize(target);
        doThrow(secondary).when(management).logout(target);

        assertThatThrownBy(() -> service.setEnabled(id, target, false))
                .isSameAs(primary).hasSuppressedException(secondary);
    }

    private UserResponse response(UUID userId, boolean enabled, String email) {
        return new UserResponse(userId, email, "Old", "Name", true, enabled,
                Set.of(UserRole.user), Instant.EPOCH, Instant.EPOCH);
    }
}
