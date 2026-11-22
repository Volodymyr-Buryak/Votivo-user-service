package com.votivo.user_service.messaging;

import com.votivo.user_service.service.UserSynchronizationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityReconciliationTest {
    @Test
    void startupAndScheduledFailuresLeaveLaterRunsPossible() {
        var synchronization = mock(UserSynchronizationService.class);
        doThrow(new IllegalStateException("sensitive upstream response"))
                .doNothing().when(synchronization).reconcile();
        var reconciliation = new IdentityReconciliation(synchronization);
        assertDoesNotThrow(reconciliation::onApplicationReady);
        assertDoesNotThrow(reconciliation::scheduledReconcile);
        verify(synchronization, times(2)).reconcile();
    }

    @Test
    void brokerTriggerFailureRemainsVisibleForRetry() {
        var synchronization = mock(UserSynchronizationService.class);
        doThrow(new IllegalStateException()).when(synchronization).reconcile();
        assertThrows(IllegalStateException.class, new IdentityReconciliation(synchronization)::reconcile);
    }
}
