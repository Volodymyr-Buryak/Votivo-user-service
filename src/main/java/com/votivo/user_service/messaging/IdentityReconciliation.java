package com.votivo.user_service.messaging;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import com.votivo.user_service.service.UserSynchronizationService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.identity", name = "sync-enabled", havingValue = "true", matchIfMissing = true)
public class IdentityReconciliation {
    private final UserSynchronizationService synchronization;

    public synchronized void reconcile() {
        synchronization.reconcile();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcileSafely();
    }

    @Scheduled(
            fixedDelayString = "${app.identity.reconcile-interval:PT1H}",
            initialDelayString = "${app.identity.reconcile-interval:PT1H}"
    )
    public void scheduledReconcile() {
        reconcileSafely();
    }

    private void reconcileSafely() {
        try {
            reconcile();
        } catch (RuntimeException exception) {
            log.warn("Identity reconciliation failed; a later reconciliation will retry");
        }
    }
}
