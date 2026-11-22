package com.votivo.user_service.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UserSearchQuery(
        @Size(max = 255) String search,
        Boolean enabled,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size
) {
    public UserSearchQuery {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
