package com.votivo.user_service.security;

import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.repository.UserProfileRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountStatus {
    private final UserProfileRepository repository;

    @Transactional(readOnly = true)
    public boolean allows(UUID subject, boolean adminRequest) {
        return repository.findById(subject)
                .map(account -> account.isEnabled() && account.getDeletedAt() == null
                        && (!adminRequest || account.getRoles().contains(UserRole.admin)))
                .orElse(true);
    }
}
