package de.makibytes.registerwerk.auth.api;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Drops the per-request session-guard cache entry of an account as soon as the change that could
 * affect it commits, so revocation takes effect immediately in this JVM (other replicas converge
 * within the cache TTL, 15 s by default — the documented revocation SLA).
 */
public class AppUserSessionStateListener {

    private static final CopyOnWriteArrayList<Consumer<UUID>> EVICTORS = new CopyOnWriteArrayList<>();

    /** Registered once by the session guard's state service. */
    public static void register(Consumer<UUID> evictor) {
        EVICTORS.addIfAbsent(evictor);
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(AppUser user) {
        UUID id = user.getId();
        if (id == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    EVICTORS.forEach(e -> e.accept(id));
                }
            });
        } else {
            EVICTORS.forEach(e -> e.accept(id));
        }
    }
}
