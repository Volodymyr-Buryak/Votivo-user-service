package com.votivo.user_service.service;

import com.votivo.user_service.dto.response.UserPageResponse;
import com.votivo.user_service.dto.response.UserResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;

public interface ProfileReader {
    Optional<UserResponse> find(UUID id);
    boolean exists(UUID id);
    UserPageResponse list(@Size(max = 255) String search, Boolean enabled,
                          @Min(0) int page, @Min(1) @Max(100) int size);
}
