package com.votivo.user_service.service;

import com.votivo.user_service.config.IdentityProperties;
import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.integration.keycloak.KeycloakAdminClient;
import com.votivo.user_service.integration.keycloak.KeycloakUserManagementClient;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.IdentityUserNotFoundException;
import com.votivo.user_service.service.impl.ProfileServiceImpl;
import com.votivo.user_service.service.impl.UserSynchronizationServiceImpl;
import com.votivo.user_service.service.support.UserLockManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserSynchronizationServiceTest {
    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final ProfileWriter writer = mock(ProfileWriter.class);
    private final UserLockManager locks = new UserLockManager();
    private final UserSynchronizationService service = new UserSynchronizationServiceImpl(keycloak, writer,
            new IdentityProperties("https://example.org", "realm", "client", "secret", 2, Duration.ofMinutes(1)), locks);

    private RemoteUser user(UUID id) { return RemoteUser.builder().id(id).email("a@b.test").firstName("A").lastName("B").emailVerified(true).enabled(true).createdTimestamp(100L).build(); }

    @Test
    void fetchesCurrentSnapshotAndFiltersRoles() {
        UUID id = UUID.randomUUID();
        RemoteUser remote = user(id);
        when(keycloak.findUser(id)).thenReturn(Optional.of(remote));
        when(keycloak.realmRoles(id)).thenReturn(Set.of("user", "admin", "offline_access"));
        service.synchronize(id);
        verify(writer).upsert(remote, Set.of(UserRole.user, UserRole.admin));
    }

    @Test
    void missingFromListingIsFetchedAgainAndOnly404Deletes() {
        UUID id = UUID.randomUUID();
        when(keycloak.listUsers(0, 2)).thenReturn(List.of());
        when(writer.activeIds()).thenReturn(List.of(id));
        when(keycloak.findUser(id)).thenReturn(Optional.empty());
        service.reconcile();
        verify(keycloak).findUser(id);
        verify(writer).softDelete(id);
    }

    @Test
    void failedListingNeverStartsDeletionSweep() {
        UUID id = UUID.randomUUID();
        RemoteUser remote = user(id);
        when(keycloak.listUsers(0, 2)).thenReturn(List.of(remote, remote));
        when(keycloak.listUsers(2, 2)).thenThrow(new IdentityAccessException("unavailable"));
        when(keycloak.findUser(id)).thenReturn(Optional.of(remote));
        when(keycloak.realmRoles(id)).thenReturn(Set.of("user"));
        assertThatThrownBy(service::reconcile).isInstanceOf(IdentityAccessException.class);
        verify(writer, never()).activeIds();
        verify(writer, never()).softDelete(any());
    }

    @Test
    void serviceAccountsNeverWriteOrFetchRoles() {
        UUID id = UUID.randomUUID();
        when(keycloak.findUser(id)).thenReturn(Optional.of(RemoteUser.builder().id(id).emailVerified(false).enabled(true).createdTimestamp(1L).serviceAccountClientId("svc").build()));
        service.synchronize(id);
        verify(keycloak, never()).realmRoles(any());
        verifyNoInteractions(writer);
    }

    @Test
    void incompleteSnapshotAndFailedRoleLookupNeverChangeExistingProfile() {
        UUID id = UUID.randomUUID();
        when(keycloak.findUser(id)).thenReturn(Optional.of(RemoteUser.builder().id(id).enabled(true).createdTimestamp(1L).build()));
        assertThatThrownBy(() -> service.synchronize(id)).isInstanceOf(IdentityAccessException.class);
        when(keycloak.findUser(id)).thenReturn(Optional.of(user(id)));
        when(keycloak.realmRoles(id)).thenThrow(new IdentityAccessException("forbidden"));
        assertThatThrownBy(() -> service.synchronize(id)).isInstanceOf(IdentityAccessException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void deletionBetweenUserAndRolesReadsIsConfirmedBeforeClearingProjection() {
        UUID id = UUID.randomUUID();
        when(keycloak.findUser(id)).thenReturn(Optional.of(user(id)), Optional.empty());
        when(keycloak.realmRoles(id)).thenThrow(new IdentityUserNotFoundException());

        service.synchronize(id);

        verify(keycloak, times(2)).findUser(id);
        verify(writer).softDelete(id);
        verify(writer, never()).upsert(any(), any());
    }

    @Test
    void rolesNotFoundDoesNotDeleteUserStillPresentUpstream() {
        UUID id = UUID.randomUUID();
        when(keycloak.findUser(id)).thenReturn(Optional.of(user(id)));
        var failure = new IdentityUserNotFoundException();
        when(keycloak.realmRoles(id)).thenThrow(failure);

        assertThatThrownBy(() -> service.synchronize(id)).isSameAs(failure);

        verifyNoInteractions(writer);
    }

    @Test
    void managementOperationWaitsUntilSnapshotWriteCompletes() throws Exception {
        UUID id = UUID.randomUUID();
        var reader = mock(ProfileReader.class);
        var management = mock(KeycloakUserManagementClient.class);
        var polls = mock(com.votivo.user_service.integration.poll.PollDeletionClient.class);
        var completion = mock(AccountDeletionCompletion.class);
        var profiles = new ProfileServiceImpl(reader, service, management, writer, locks, polls, completion);
        when(reader.find(id)).thenReturn(Optional.of(new UserResponse(id, "a@b.test", "A", "B", true, true,
                Set.of(UserRole.user), Instant.EPOCH, Instant.EPOCH)));
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(keycloak.findUser(id)).thenAnswer(invocation -> {
            fetchStarted.countDown();
            if (!releaseFetch.await(5, TimeUnit.SECONDS)) throw new AssertionError("fetch was not released");
            return Optional.of(user(id));
        });
        when(keycloak.realmRoles(id)).thenReturn(Set.of("user"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var snapshot = executor.submit(() -> service.synchronize(id));
            org.assertj.core.api.Assertions.assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> profiles.deleteOwn(id));
            try {
                assertThatThrownBy(() -> deletion.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                verify(management, never()).delete(id);
                verify(writer, never()).softDelete(id);
            } finally {
                releaseFetch.countDown();
            }
            snapshot.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
        }
        var order = inOrder(writer, polls, completion);
        order.verify(writer, times(2)).upsert(any(), any());
        order.verify(polls).reserve(id);
        order.verify(writer).requestDeletion(id);
        order.verify(completion).complete(id);
        verify(management, never()).delete(any());
    }

    @Test
    void serializesCurrentFetchAndWriteForConcurrentEventsOfTheSameUser() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch firstFetch = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(keycloak.findUser(id)).thenAnswer(invocation -> {
            firstFetch.countDown();
            if (!releaseFetch.await(5, TimeUnit.SECONDS)) throw new AssertionError("fetch was not released");
            return Optional.of(user(id));
        });
        when(keycloak.realmRoles(id)).thenReturn(Set.of("user"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.synchronize(id));
            org.assertj.core.api.Assertions.assertThat(firstFetch.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.synchronize(id));
            try {
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                verify(keycloak, times(1)).findUser(id);
            } finally {
                releaseFetch.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        verify(writer, times(2)).upsert(any(), eq(Set.of(UserRole.user)));
    }
}
