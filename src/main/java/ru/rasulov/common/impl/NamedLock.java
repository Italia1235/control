package ru.rasulov.common.impl;

import com.atlassian.beehive.ClusterLockService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.exception.BusyException;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Runs a piece of work under a cluster-wide lock (works in Data Center too) so that two overlapping calls cannot both
 * pass a "does it exist yet?" check and then both write. Waits {@value #WAIT_SECONDS} seconds for the lock, then gives
 * up with {@link BusyException}; nothing has been changed by then.
 */
@Named
public class NamedLock {

    public static final long WAIT_SECONDS = 60;

    private final ClusterLockService clusterLockService;

    @Inject
    public NamedLock(@ComponentImport ClusterLockService clusterLockService) {
        this.clusterLockService = clusterLockService;
    }

    /**
     * @param name lock name; different names do not block each other
     * @param what what the call changes, for the message of the busy exception ("custom fields", "screens")
     * @throws BusyException the lock was not free in time
     */
    public <T> T run(String name, String what, Supplier<T> work) {
        Lock lock = clusterLockService.getLockForName(name);
        if (!acquire(lock)) {
            throw new BusyException("Another call is changing " + what + " and did not finish within "
                    + WAIT_SECONDS + " seconds. Nothing was changed; retry later.");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code work} holding the first free lock of the fixed pool {@code prefix + 0 .. prefix + (slots - 1)}, trying
     * them without waiting from {@code start}. The pool has constant names: Beehive keeps every lock name in the database
     * forever, so a name per object would grow without bound.
     *
     * @param work receives the number of the held slot
     * @throws BusyException every slot is held; nothing has been changed by then
     */
    public <T> T runOnFreeSlot(String prefix, int slots, int start, String what, IntFunction<T> work) {
        for (int i = 0; i < slots; i++) {
            int slot = Math.floorMod(start + i, slots);
            Lock lock = clusterLockService.getLockForName(prefix + slot);
            if (!lock.tryLock()) continue;
            try {
                return work.apply(slot);
            } finally {
                lock.unlock();
            }
        }
        throw new BusyException("All " + slots + " slots for " + what + " are busy. Nothing was changed; retry later.");
    }

    private static boolean acquire(Lock lock) {
        try {
            return lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
