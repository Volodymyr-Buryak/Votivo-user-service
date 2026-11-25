package com.votivo.user_service.entity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileTest {
    @Test
    void synchronizesProfilesBuiltWithImmutableRoles() {
        var profile = UserProfile.builder().id(UUID.randomUUID()).roles(Set.of(UserRole.user)).build();
        var now = Instant.now();

        profile.synchronize("a@example.org", "A", "B", true, true,
                Set.of(UserRole.admin), now, now);

        assertThat(profile.getRoles()).containsExactly(UserRole.admin);
    }

    @Test
    void repeatedSnapshotAdvancesSyncTimeButOnlyChangesAdvanceUpdatedTime() {
        var profile = UserProfile.builder().id(UUID.randomUUID()).build();
        var created = Instant.parse("2020-01-01T00:00:00Z");
        var first = created.plusSeconds(100);
        profile.synchronize("a@example.org", "A", "B", true, true, Set.of(UserRole.user), created, first);
        profile.synchronize("a@example.org", "A", "B", true, true, Set.of(UserRole.user), created, first.plusSeconds(1));
        assertThat(profile.getCreatedAt()).isEqualTo(created);
        assertThat(profile.getUpdatedAt()).isEqualTo(first);
        assertThat(profile.getSyncedAt()).isEqualTo(first.plusSeconds(1));
        profile.synchronize("b@example.org", "A", "B", true, true, Set.of(UserRole.admin), created, first.plusSeconds(2));
        assertThat(profile.getUpdatedAt()).isEqualTo(first.plusSeconds(2));
        assertThat(profile.getRoles()).containsExactly(UserRole.admin);
    }

    @Test
    void deletionClearsPersonalDataAndCannotBeRestoredBySynchronization() {
        UUID id = UUID.randomUUID();
        var profile = UserProfile.builder().id(id).build();
        var now = Instant.now();
        profile.synchronize("a@example.org", "A", "B", true, true, Set.of(UserRole.admin), now, now);
        profile.softDelete(now.plusSeconds(1));
        profile.softDelete(now.plusSeconds(2));
        assertThat(profile.getId()).isEqualTo(id);
        assertThat(profile.getEmail()).isNull();
        assertThat(profile.getFirstName()).isNull();
        assertThat(profile.getLastName()).isNull();
        assertThat(profile.getRoles()).isEmpty();
        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.isEmailVerified()).isFalse();
        assertThat(profile.getDeletedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(profile.getUpdatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(profile.getSyncedAt()).isEqualTo(now.plusSeconds(2));
        profile.synchronize("new@example.org", null, null, false, true, Set.of(UserRole.user), now, now.plusSeconds(3));
        assertThat(profile.getDeletedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(profile.getEmail()).isNull();
    }
}
