package com.votivo.user_service.service;

import java.util.UUID;

public interface UserSynchronizationService {
    void synchronize(UUID id);
    void reconcile();
}
