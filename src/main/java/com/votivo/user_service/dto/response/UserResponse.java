package com.votivo.user_service.dto.response;

import com.votivo.user_service.entity.UserRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;

@Builder
public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        boolean emailVerified,
        boolean enabled,
        Set<UserRole> roles,
        Instant createdAt,
        Instant updatedAt
) {
    public UserResponse {
        roles = Set.copyOf(roles);
    }
}
