package euhedral.io.hardware_utils.pinning;

import static euhedral.io.utils.MathFunctions.clampInt;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import net.openhft.affinity.AffinityLock;
import org.jctools.maps.NonBlockingHashSet;
import org.jspecify.annotations.NonNull;

public class PinnedThreadExecutor extends AbstractExecutorService implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ConcurrentHashMap<Long, WeakReference<PinnedThreadExecutor>> PINNED_EXECUTORS = new ConcurrentHashMap<>(
            512);

    public static PinnedThreadExecutor getOrSetIfAbsent(long cpu, String name, int priority,
            boolean daemon) {
        var exec = get(cpu);
        if (exec == null) {
            PinnedThreadExecutor executor = new PinnedThreadExecutor(name, (int) cpu, priority,
                    daemon);
            PINNED_EXECUTORS.put(cpu, new WeakReference<>(executor));
            return executor;
        } else if(exec.isShutdown()) {
            exec.start(name, priority, daemon);
        }
        return exec;
    }

    public static PinnedThreadExecutor get(long cpu) {
        var executor = PINNED_EXECUTORS.get(cpu);
        if (executor != null) {
            if (executor.get() != null && !executor.get().isShutdown()) {
                return executor.get();
            }
            PINNED_EXECUTORS.remove(cpu);
            return null;
        }
        return null;
    }

    public static void closeAll() {
        for (var exec : PINNED_EXECUTORS.values()) {
            PinnedThreadExecutor executor = exec.get();
            if (executor != null) {
                executor.close();
            }
        }
    }

    @Getter
    private final ThreadFactory pinnedFactory;
    private final AtomicReference<AffinityLock> lock = new AtomicReference<>();
    private final CleanupState cleanupState;
    private final AtomicBoolean isShutdown = new AtomicBoolean();
    private final NonBlockingHashSet<Thread> threadPool = new NonBlockingHashSet<>();
    private final Thread cleanup;

    @Getter
    private final int cpu;

    private volatile String name;
    private volatile int priority;
    private volatile boolean daemon;

    private PinnedThreadExecutor(String name, int cpu, int priority, boolean daemon) {
        this.cpu = cpu;
        this.name = name;
        this.priority = priority;
        this.daemon = daemon;
        this.pinnedFactory = runnable -> {
            Thread thread = new Thread(() -> {
                try (AffinityLock al = AffinityLock.acquireLock(cpu)) {
                    lock.set(al);

                    runnable.run();
                } catch (Throwable e) {
                    System.out.printf("PinnedThreadExecutor: [%s] encountered an error: %s\n", this.name,
                            e);
                } finally {
                    if (lock.get() != null) {
                        lock.get().close();
                    }
                }
            });
            thread.setName(this.name);
            thread.setPriority(clampInt(this.priority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY));
            thread.setDaemon(this.daemon);
            return thread;
        };
        this.cleanup = new Thread(() -> {
            while (!Thread.interrupted()) {
                threadPool.removeIf(t -> !t.isAlive());
                LockSupport.parkNanos(200_000_000L);
            }
        });
        this.cleanup.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownNow));

        this.cleanupState = new CleanupState(cpu, this, lock);
        CLEANER.register(this, this.cleanupState);
    }

    public void start(String name, int priority, boolean daemon) {
        if(!isShutdown.compareAndSet(true, false)) {
            System.err.println("This PinnedThreadExecutor is still running.\n" + Arrays.toString(
                    Thread.currentThread().getStackTrace()));
            return;
        }
        this.name = name;
        this.priority = priority;
        this.daemon = daemon;
        PINNED_EXECUTORS.put(cleanupState.cpu, new WeakReference<>(this));
    }

    @Override
    public @NonNull List<Runnable> shutdownNow() {
        if (!isShutdown.compareAndSet(false, true)) {
            return Collections.emptyList();
        }
        try {
            cleanup.interrupt();
            LockSupport.unpark(cleanup);
            cleanup.join();
        } catch (Throwable ignored) {

        }
        for (Thread thread : threadPool) {
            try {
                thread.interrupt();
                thread.join();
            } catch (Throwable ignored) {

            }
        }
        threadPool.clear();
        return Collections.emptyList();
    }

    @Override
    public void execute(@NonNull Runnable command) {
        if (isShutdown.get()) {
            throw new RejectedExecutionException();
        }
        Thread thread = pinnedFactory.newThread(command);
        threadPool.add(thread);
        thread.start();
    }

    /**
     * The actual vThread implementation is under src/main/java21
     *
     * @param runnable
     * @return
     */
    public Thread vThread(@NonNull Runnable runnable) {
        return new Thread(runnable, name + "-fakeVThread");
    }

    public ThreadFactory vThreadFactory() {
        return r -> new Thread(r, name + "-fakeVThread");
    }

    @Override
    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return;
        }
        try {
            cleanup.interrupt();
            LockSupport.unpark(cleanup);
            cleanup.join();
        } catch (Throwable ignored) {

        }
        for (Thread thread : threadPool) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isShutdown() {
        return isShutdown.get();
    }

    @Override
    public void close() {
        cleanupState.run();
    }
    @Override
    public boolean isTerminated() {
        if (!isShutdown.get()) {
            return false;
        }

        for (Thread thread : threadPool) {
            if (thread.isAlive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
        if (!isShutdown.get()) {
            return false;
        }
        if (threadPool.isEmpty()) {
            return true;
        }
        long nanos = unit.toNanos(timeout);
        long parkNs = nanos;
        if (nanos > 50) {
            parkNs = 50;
        }

        long now = System.nanoTime();
        long deadline = now + nanos;
        while ((now = System.nanoTime()) < deadline) {
            boolean isAlive = false;
            for (Thread thread : threadPool) {
                if (thread.isAlive()) {
                    isAlive = true;
                    break;
                }
            }
            if (!isAlive) {
                threadPool.clear();
                return true;
            }
            if (deadline - now > parkNs) {
                LockSupport.parkNanos(parkNs);
            }
        }
        return false;
    }

    private static class CleanupState implements Runnable {

        private final long cpu;
        private final PinnedThreadExecutor executor;
        private final AtomicReference<AffinityLock> lock;

        CleanupState(long cpu, PinnedThreadExecutor executor,
                AtomicReference<AffinityLock> lock) {
            this.cpu = cpu;
            this.executor = executor;
            this.lock = lock;
        }

        @Override
        public void run() {
            executor.shutdownNow();
            AffinityLock al = lock.getAndSet(null);
            if (al != null) {
                al.close();
            }

            PINNED_EXECUTORS.remove(cpu);
            System.out.println("Cleaned up CPU " + cpu);
        }
    }
}
