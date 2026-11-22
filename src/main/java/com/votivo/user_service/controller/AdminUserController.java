package com.votivo.user_service.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.votivo.user_service.dto.request.*;
import com.votivo.user_service.dto.response.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.oauth2.jwt.Jwt;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.dto.request.UserStatusRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final ProfileService profiles;

    @GetMapping
    public UserPageResponse list(@Valid @ModelAttribute UserSearchQuery query) {
        return profiles.list(query.search(), query.enabled(), query.page(), query.size());
    }

    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable UUID id) {
        return profiles.getById(id);
    }

    @PatchMapping("/{id}/status")
    public UserResponse setEnabled(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UserStatusRequest request) {
        return profiles.setEnabled(UUID.fromString(jwt.getSubject()), id, request.enabled());
    }

}
