package com.votivo.user_service.controller;

import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.security.AccountStatus;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Gateway-only check. No browser token or DPoP proof is forwarded to a different URL. */
@RestController
@RequestMapping("/internal/v1/users")
public class AccountAccessController {
    private final ProfileService profiles;
    private final String secret;
    private final AccountStatus status;

    public AccountAccessController(ProfileService profiles, AccountStatus status,
            @Value("${app.security.internal-secret:}") String secret) {
        this.profiles = profiles;
        this.secret = secret;
        this.status = status;
    }

    @GetMapping("/{id}/access")
    public ResponseEntity<Void> access(@PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean admin,
            @RequestParam(defaultValue = "0") long issuedAt,
            @RequestHeader(name = "X-Internal-Auth", required = false) String supplied) {
        if (secret.isBlank()) return ResponseEntity.status(503).build();
        if (supplied == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        if (issuedAt <= 0) return ResponseEntity.badRequest().build();
        var account = profiles.getOwn(id);
        if (admin && !account.roles().contains(UserRole.admin)) return ResponseEntity.status(403).build();
        if (!status.allows(id, admin, Instant.ofEpochSecond(issuedAt))) return ResponseEntity.status(403).build();
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
