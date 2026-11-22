package com.votivo.user_service.dto.response;

import java.util.List;
import lombok.Builder;

@Builder
public record UserPageResponse(
        List<UserResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public UserPageResponse {
        items = List.copyOf(items);
    }
}
