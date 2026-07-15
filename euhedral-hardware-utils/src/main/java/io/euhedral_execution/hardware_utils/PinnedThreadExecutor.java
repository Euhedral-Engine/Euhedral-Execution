package io.euhedral_execution.hardware_utils;

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
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PinnedThreadExecutor extends AbstractExecutorService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PinnedThreadExecutor.class);

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ConcurrentHashMap<Long, WeakReference<PinnedThreadExecutor>> PINNED_EXECUTORS = new ConcurrentHashMap<>(
            512);

    public static PinnedThreadExecutor getOrSetIfAbsent(long cpu, String name, int priority,
            boolean daemon) {
        return getOrSetIfAbsent(Thread::new, cpu, name, priority, daemon);
    }

    public static PinnedThreadExecutor getOrSetIfAbsent(Function<Runnable, ? extends Thread> threadCreator,
            long cpu, String name, int priority,
            boolean daemon) {
        var exec = get(cpu);
        if (exec == null) {
            PinnedThreadExecutor executor = new PinnedThreadExecutor(threadCreator, name, (int) cpu, priority,
                    daemon);
            PINNED_EXECUTORS.put(cpu, new WeakReference<>(executor));
            return executor;
        } else if (exec.isShutdown()) {
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
    private final CleanupState cleanupState;
    private final AtomicBoolean isShutdown = new AtomicBoolean();
    private final ConcurrentHashMap<Thread, Boolean> threadPool = new ConcurrentHashMap<>();

    @Getter
    private final int cpu;

    private volatile String name;
    private volatile int priority;
    private volatile boolean daemon;

    private PinnedThreadExecutor(Function<Runnable, ? extends Thread> threadCreator, String name, int cpu,
            int priority, boolean daemon) {
        this.cpu = cpu;
        this.name = name;
        this.priority = priority;
        this.daemon = daemon;
        this.pinnedFactory = runnable -> {
            Thread thread = threadCreator.apply(() -> {
                try {
                    ThreadTools.setAffinity(cpu);

                    runnable.run();
                } catch (Throwable e) {
                    LOGGER.error("PinnedThreadExecutor: [{}] encountered an error.", this.name, e);
                } finally {
                    ThreadTools.releaseAffinity();
                    this.threadPool.remove(Thread.currentThread());
                }
            });
            thread.setName(this.name);
            thread.setPriority(
                    Math.max(Thread.MIN_PRIORITY, Math.min(this.priority, Thread.MAX_PRIORITY)));
            thread.setDaemon(this.daemon);
            return thread;
        };
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownNow));

        this.cleanupState = new CleanupState(cpu, this);
        CLEANER.register(this, this.cleanupState);
    }

    public void start(String name, int priority, boolean daemon) {
        if (!isShutdown.compareAndSet(true, false)) {
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("PinnedThreadExecutor: [{}] is already started\n{}.", this.name,
                        Arrays.toString(Thread.currentThread().getStackTrace()));
            }
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
        for (Thread thread : threadPool.keySet()) {
            try {
                thread.interrupt();
                LockSupport.unpark(thread);
                thread.interrupt();
                thread.join(500);
            } catch (Exception ignored) {
                // Ignore interrupt on close.
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
        threadPool.put(thread, true);
        thread.start();
    }

    @Override
    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return;
        }
        for (Thread thread : threadPool.keySet()) {
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

        for (Thread thread : threadPool.keySet()) {
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
            for (Thread thread : threadPool.keySet()) {
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

        CleanupState(long cpu, PinnedThreadExecutor executor) {
            this.cpu = cpu;
            this.executor = executor;
        }

        @Override
        public void run() {
            executor.shutdownNow();

            PINNED_EXECUTORS.remove(cpu);
            LOGGER.trace("Cleaned up PinnedThreadExecutor [{}] CPU [{}]", executor.name, cpu);
        }
    }
}
