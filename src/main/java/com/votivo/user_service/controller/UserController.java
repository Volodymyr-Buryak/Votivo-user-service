package com.votivo.user_service.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.oauth2.jwt.Jwt;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.dto.response.UserHeaderResponse;
import com.votivo.user_service.dto.request.UpdateProfileRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {
    private final ProfileService profiles;

    @GetMapping
    public UserResponse getOwn(@AuthenticationPrincipal Jwt jwt) {
        return profiles.getOwn(UUID.fromString(jwt.getSubject()));
    }

    @PatchMapping
    public UserResponse updateOwn(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return profiles.updateOwn(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping("/header")
    public UserHeaderResponse getOwnHeader(@AuthenticationPrincipal Jwt jwt) {
        return profiles.getOwnHeader(UUID.fromString(jwt.getSubject()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwn(@AuthenticationPrincipal Jwt jwt) {
        profiles.deleteOwn(UUID.fromString(jwt.getSubject()));
    }

}
