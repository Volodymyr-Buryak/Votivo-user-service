package com.votivo.user_service.dto.request;

import jakarta.validation.constraints.NotNull;

public record UserStatusRequest(
        @NotNull Boolean enabled
) {}
