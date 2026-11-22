package com.votivo.user_service.service.support;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Orders management writes and projection snapshots for the same user in this instance. */
@Component
public class UserLockManager {
    private final ReentrantLock[] locks = new ReentrantLock[256];

    public UserLockManager() {
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    public <T> T withUserLock(UUID id, Supplier<T> operation) {
        ReentrantLock lock = locks[Math.floorMod(id.hashCode(), locks.length)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
