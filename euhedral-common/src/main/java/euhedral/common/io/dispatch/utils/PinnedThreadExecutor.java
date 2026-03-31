package euhedral.common.io.dispatch.utils;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import net.openhft.affinity.AffinityLock;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jspecify.annotations.NonNull;

public class PinnedThreadExecutor implements Executor, AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final ConcurrentHashMap<Long, WeakReference<PinnedThreadExecutor>> PINNED_EXECUTORS = new ConcurrentHashMap<>(
            512);

    public static PinnedThreadExecutor get(long cpu) {
        var executor = PINNED_EXECUTORS.get(cpu);
        if(executor != null) {
            return executor.get();
        }
        return null;
    }

    public static PinnedThreadExecutor getOrSetIfAbsent(long cpu, String name, int priority,
            boolean daemon) {
        return PINNED_EXECUTORS.computeIfAbsent(cpu,
                (_) -> new WeakReference<>(new PinnedThreadExecutor(name, (int) cpu, priority, daemon))).get();
    }

    private final String name;
    private final ExecutorService pinnedService;
    private final ExecutorService pinnedSubService;
    @Getter
    private final ThreadFactory pinnedFactory;
    private final AtomicReference<AffinityLock> lock = new AtomicReference<>();
    private final CleanupState cleanupState;

    private PinnedThreadExecutor(String name, int cpu, int priority, boolean daemon) {
        this.name = name;
        this.pinnedFactory = runnable -> {
            Thread thread = new Thread(() -> {
                try (AffinityLock al = AffinityLock.acquireLock(cpu)) {
                    lock.set(al);

                    runnable.run();
                } catch (Throwable e) {
                    System.out.printf("PinnedThreadExecutor: [%s] encountered an error: %s\n", name,
                            e);
                } finally {
                    if (lock.get() != null) {
                        lock.get().close();
                    }
                }
            });
            thread.setName(name);
            thread.setPriority(Math.clamp(priority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY));
            thread.setDaemon(daemon);
            return thread;
        };
        this.pinnedService = Executors.newSingleThreadExecutor(this.pinnedFactory);
        this.pinnedSubService = Executors.newThreadPerTaskExecutor(this.pinnedFactory);

        this.cleanupState = new CleanupState(cpu, pinnedService, pinnedSubService, lock);
        CLEANER.register(this, this.cleanupState);
    }

    @Override
    public void execute(@NonNull Runnable command) {
        pinnedService.execute(command);
    }

    public void subExecute(@NonNull Runnable command) {
        pinnedSubService.execute(command);
    }

    public Thread vThread(@NonNull Runnable runnable) {
        return Thread.ofVirtual()
                .name(name + "-vthread")
                .unstarted(runnable);
    }

    public ThreadFactory vThreadFactory() {
        return Thread.ofVirtual()
                .name(name + "-vthread-", 0)
                .factory();
    }

    @Override
    public void close() {
        cleanupState.run();
    }

    private static class CleanupState implements Runnable {

        private final long cpu;
        private final ExecutorService s1;
        private final ExecutorService s2;
        private final AtomicReference<AffinityLock> lock;

        CleanupState(long cpu, ExecutorService s1, ExecutorService s2,
                AtomicReference<AffinityLock> lock) {
            this.cpu = cpu;
            this.s1 = s1;
            this.s2 = s2;
            this.lock = lock;
        }

        @Override
        public void run() {
            // This runs when the PinnedThreadExecutor is GC'd OR close() is called
            s1.shutdownNow();
            s2.shutdownNow();
            AffinityLock al = lock.getAndSet(null);
            if (al != null) {
                al.close();
            }

            // Remove from map if this specific instance is still there
            PINNED_EXECUTORS.remove(cpu);
            System.out.println("Cleaned up CPU " + cpu);
        }
    }
}
