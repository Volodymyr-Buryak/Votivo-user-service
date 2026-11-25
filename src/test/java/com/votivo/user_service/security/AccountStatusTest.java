package com.votivo.user_service.security;

import com.votivo.user_service.entity.UserProfile;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.repository.UserProfileRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AccountStatusTest {
    @Test void tokensIssuedBeforeOrAtRevocationStayInvalidAfterUnblocking() {
        var repository = mock(UserProfileRepository.class);
        var id = UUID.randomUUID();
        var cutoff = Instant.parse("2026-11-25T00:00:00Z");
        var user = UserProfile.builder().id(id).enabled(true).sessionsRevokedAt(cutoff)
                .roles(Set.of(UserRole.user)).build();
        when(repository.findById(id)).thenReturn(Optional.of(user));
        var status = new AccountStatus(repository);
        assertThat(status.allows(id, false, null)).isFalse();
        assertThat(status.allows(id, false, cutoff.minusSeconds(1))).isFalse();
        assertThat(status.allows(id, false, cutoff)).isFalse();
        assertThat(status.allows(id, false, cutoff.plusSeconds(1))).isTrue();
        assertThat(status.allows(id, true, cutoff.plusSeconds(1))).isFalse();
    }
}
