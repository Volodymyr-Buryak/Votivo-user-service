package com.votivo.user_service.security;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@RequiredArgsConstructor
public final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        boolean validSubject = false;
        try {
            validSubject = token.getSubject() != null
                    && UUID.fromString(token.getSubject()).toString().equals(token.getSubject());
        } catch (IllegalArgumentException ignored) {
            // UUID.fromString also accepts shortened forms; require the canonical representation.
        }

        if (!validSubject || token.getExpiresAt() == null || token.getAudience() == null
                || !token.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }

        return OAuth2TokenValidatorResult.success();
    }
}
