package com.votivo.user_service.service.impl;

import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.entity.UserProfile;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.repository.UserProfileRepository;
import com.votivo.user_service.service.ProfileWriter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileWriterImpl implements ProfileWriter {
    private final UserProfileRepository repository;

    @Transactional
    @Override
    public void upsert(RemoteUser source, Set<UserRole> roles) {
        UserProfile profile = repository.findById(source.id()).orElseGet(() -> UserProfile.builder().id(source.id()).build());
        profile.synchronize(source.email(), source.firstName(), source.lastName(),
                Boolean.TRUE.equals(source.emailVerified()), Boolean.TRUE.equals(source.enabled()), roles,
                source.createdTimestamp() == null ? null : Instant.ofEpochMilli(source.createdTimestamp()),
                Instant.now());
        repository.save(profile);
    }

    @Transactional
    @Override
    public void softDelete(UUID id) {
        repository.findById(id).ifPresent(profile -> {
            profile.softDelete(Instant.now());
            repository.save(profile);
        });
    }

    @Transactional(readOnly = true)
    @Override
    public List<UUID> activeIds() {
        return repository.findActiveIds();
    }
}
