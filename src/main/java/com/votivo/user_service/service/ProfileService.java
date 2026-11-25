package com.votivo.user_service.service;

import com.votivo.user_service.dto.request.UpdateProfileRequest;
import com.votivo.user_service.dto.response.UserPageResponse;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.dto.response.UserHeaderResponse;
import java.util.UUID;

public interface ProfileService {
    UserResponse getOwn(UUID id);
    UserHeaderResponse getOwnHeader(UUID id);
    UserResponse getById(UUID id);
    UserResponse updateOwn(UUID id, UpdateProfileRequest request);
    boolean deleteOwn(UUID id);
    UserPageResponse list(String search, Boolean enabled, int page, int size);
    UserResponse setEnabled(UUID actorId, UUID targetId, boolean enabled);
}
