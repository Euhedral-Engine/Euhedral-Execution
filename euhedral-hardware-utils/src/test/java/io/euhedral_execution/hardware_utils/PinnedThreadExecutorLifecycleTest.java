package io.euhedral_execution.hardware_utils;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Deterministic schedules for the complete P3-B executor lifecycle and cleanup contract.
class PinnedThreadExecutorLifecycleTest {

    private static final long WAIT_SECONDS = 5;

    /// Runs one bounded execute-versus-shutdown stress schedule.
    private static void stressExecuteAgainstShutdown(Harness harness, int cpu, int round) throws Exception {
        PinnedThreadExecutor executor = harness.acquire(cpu, Thread::new);
        CyclicBarrier barrier = new CyclicBarrier(9);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger liveCommands = new AtomicInteger();
        AtomicInteger maximumCommands = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> submitters = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread submitter = new Thread(
                    () -> {
                        await(barrier);
                        try {
                            executor.execute(() -> {
                                int live = liveCommands.incrementAndGet();
                                maximumCommands.accumulateAndGet(live, Math::max);
                                try {
                                    awaitIgnoringInterrupt(release);
                                } finally {
                                    liveCommands.decrementAndGet();
                                }
                            });
                        } catch (RejectedExecutionException expected) {
                            // The shutdown side won this bounded acceptance race.
                        } catch (Throwable failure) {
                            failures.add(failure);
                        }
                    },
                    "stress-submit-" + round + "-" + i);
            submitters.add(submitter);
            submitter.start();
        }
        await(barrier);
        executor.shutdown();
        joinAll(submitters);
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS));
        executor.close();
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(maximumCommands.get() <= 8);
    }

    /// Runs one bounded close-versus-acquisition stress schedule.
    private static void stressCloseAgainstAcquire(Harness harness, int cpu, int round) throws Exception {
        PinnedThreadExecutor executor = harness.acquire(cpu, Thread::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
        });
        assertTrue(entered.await(WAIT_SECONDS, SECONDS));

        CyclicBarrier barrier = new CyclicBarrier(9);
        ConcurrentLinkedQueue<PinnedThreadExecutor> returned = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> acquirers = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread acquirer = new Thread(
                    () -> {
                        await(barrier);
                        try {
                            returned.add(harness.acquire(cpu, Thread::new));
                        } catch (RejectedExecutionException expected) {
                            // The close side won after making the old active entry a tombstone.
                        } catch (Throwable failure) {
                            failures.add(failure);
                        }
                    },
                    "stress-acquire-" + round + "-" + i);
            acquirers.add(acquirer);
            acquirer.start();
        }
        await(barrier);
        executor.close();
        joinAll(acquirers);
        assertTrue(returned.stream().allMatch(candidate -> candidate == executor));
        assertTrue(failures.isEmpty(), failures.toString());
        release.countDown();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS));
        awaitCondition(() -> harness.registry.entryCount() == 0);
    }

    /// Selects one available logical CPU for validation-compatible isolated tests.
    private static int testCpu() {
        int cpu = ThreadTools.BASE_MASK.nextSetBit(0);
        assertTrue(cpu >= 0, "no CPU is available for the test");
        return cpu;
    }

    /// Selects another valid CPU when the reported logical span permits one.
    private static int alternateCpu(int cpu) {
        if (SystemInfo.getCpuCount() <= 1) {
            return cpu;
        }
        return cpu == 0 ? 1 : 0;
    }

    /// Joins every deterministic helper thread within the shared diagnostic bound.
    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            join(thread);
        }
    }

    /// Joins one helper thread and fails with its identity if it remains live.
    private static void join(Thread thread) throws InterruptedException {
        thread.join(SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(thread.isAlive(), () -> "thread did not finish: " + thread.getName());
    }

    /// Awaits a latch without leaking checked interruption into command lambdas.
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, SECONDS), "latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting latch", interrupted);
        }
    }

    /// Awaits one bounded barrier phase from a helper thread.
    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("barrier failed", failure);
        }
    }

    /// Keeps a deliberate interrupt-ignoring command active until explicit release.
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() != 0) {
            try {
                if (!latch.await(WAIT_SECONDS, SECONDS)) {
                    throw new AssertionError("latch timed out");
                }
            } catch (InterruptedException expected) {
                // Deliberately keep the task active until the deterministic release.
            }
        }
    }

    /// Waits boundedly for a deterministic JVM thread-state boundary.
    private static void awaitThreadState(Thread thread, Thread.State expected) {
        long started = System.nanoTime();
        long budget = SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != expected) {
            assertTrue(
                    System.nanoTime() - started < budget,
                    () -> "thread state was " + thread.getState() + ", expected " + expected);
            Thread.onSpinWait();
        }
    }

    /// Waits boundedly until an awaitTermination helper is parked on its monitor.
    private static void awaitWaiting(Thread thread) {
        long started = System.nanoTime();
        long budget = SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() - started < budget, () -> "thread did not enter a wait: " + thread.getState());
            Thread.onSpinWait();
        }
    }

    /// Spins boundedly on a low-frequency deterministic cleanup condition.
    private static void awaitCondition(BooleanSupplier condition) {
        long started = System.nanoTime();
        long budget = SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - started < budget, "condition timed out");
            Thread.onSpinWait();
        }
    }

    /// Traverses the isolated cleanup graph while treating weak references as boundaries.
    private static void assertNoForbiddenReachability(Object root) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Object value = pending.removeFirst();
            if (!seen.add(value)
                    || value instanceof Reference<?>
                    || value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean
                    || value instanceof Character
                    || value.getClass().isEnum()
                    || value instanceof Class<?>) {
                continue;
            }
            assertFalse(value instanceof PinnedThreadExecutor, "cleanup graph retained an executor");
            assertFalse(value instanceof java.util.concurrent.ThreadFactory, "cleanup graph retained a pinned factory");
            assertFalse(value instanceof Thread, "cleanup graph retained a task thread");
            if (value instanceof Iterable<?> iterable) {
                iterable.forEach(element -> addIfNotNull(pending, element));
            }
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, element) -> {
                    addIfNotNull(pending, key);
                    addIfNotNull(pending, element);
                });
            }
            Class<?> type = value.getClass();
            if (!type.getName().startsWith("io.euhedral_execution.hardware_utils")) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertFalse(field.getName().startsWith("this$"), () -> "synthetic outer reference: " + field);
                try {
                    field.setAccessible(true);
                    addIfNotNull(pending, field.get(value));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }
        }
    }

    /// Adds a non-null graph node to the structural traversal work queue.
    private static void addIfNotNull(ArrayDeque<Object> pending, Object value) {
        if (value != null) {
            pending.add(value);
        }
    }

    /// Proves E1 singleton acquisition across 32 simultaneous callers.
    @Test
    void e1LinearizesConcurrentSingletonAcquisition() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        int callers = 32;
        CyclicBarrier barrier = new CyclicBarrier(callers);
        ConcurrentLinkedQueue<PinnedThreadExecutor> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                Thread caller = new Thread(
                        () -> {
                            try {
                                barrier.await(WAIT_SECONDS, SECONDS);
                                results.add(harness.acquire(cpu, Thread::new));
                            } catch (Throwable failure) {
                                failures.add(failure);
                            }
                        },
                        "e1-acquirer-" + i);
                threads.add(caller);
                caller.start();
            }
            joinAll(threads);

            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(callers, results.size());
            PinnedThreadExecutor winner = results.peek();
            assertNotNull(winner);
            assertTrue(results.stream().allMatch(candidate -> candidate == winner));
            assertEquals(1, harness.registry.entryCount());
            assertEquals(1, harness.cleanups.registrations.get());
            assertEquals(1, harness.cleanups.live.get());
            assertEquals(1, harness.hooks.adds.get());
            assertEquals(1, harness.hooks.live.get());
            assertEquals(1, harness.hooks.maximumLive.get());
        } finally {
            harness.closeAll();
        }
        harness.assertClean();
    }

    /// Provides the A02 anchor and forces the E2/E3 execute-shutdown boundaries.
    @Test
    void linearizesExecuteShutdownAndCleanup() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        CountDownLatch firstCreated = new CountDownLatch(1);
        CountDownLatch returnFirst = new CountDownLatch(1);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStart = new CountDownLatch(1);
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<Thread> rejectedCandidate = new AtomicReference<>();
        Function<Runnable, Thread> creator = command -> {
            int creation = creations.getAndIncrement();
            if (creation == 0) {
                Thread candidate = new Thread(command, "e2-candidate");
                rejectedCandidate.set(candidate);
                firstCreated.countDown();
                await(returnFirst);
                return candidate;
            }
            return new Thread(command, "e3-candidate") {
                /// Pauses start while execute still owns the lifecycle monitor.
                @Override
                public synchronized void start() {
                    startEntered.countDown();
                    await(allowStart);
                    super.start();
                }
            };
        };

        PinnedThreadExecutor executor = harness.acquire(cpu, creator);
        AtomicReference<Throwable> e2Outcome = new AtomicReference<>();
        Thread e2Execute = new Thread(
                () -> {
                    try {
                        executor.execute(() -> {});
                    } catch (Throwable failure) {
                        e2Outcome.set(failure);
                    }
                },
                "e2-execute");
        e2Execute.start();
        assertTrue(firstCreated.await(WAIT_SECONDS, SECONDS));
        executor.shutdown();
        returnFirst.countDown();
        join(e2Execute);
        assertInstanceOf(RejectedExecutionException.class, e2Outcome.get());
        assertEquals(Thread.State.NEW, rejectedCandidate.get().getState());
        assertEquals(0, harness.control(cpu).activeTaskCount());

        assertSame(executor, harness.acquire(cpu, Thread::new));
        Thread e3Execute = new Thread(
                () -> executor.execute(() -> {
                    taskEntered.countDown();
                    await(taskRelease);
                }),
                "e3-execute");
        e3Execute.start();
        assertTrue(startEntered.await(WAIT_SECONDS, SECONDS));

        Thread shutdown = new Thread(executor::shutdown, "e3-shutdown");
        shutdown.start();
        awaitThreadState(shutdown, Thread.State.BLOCKED);
        allowStart.countDown();
        assertTrue(taskEntered.await(WAIT_SECONDS, SECONDS));
        join(e3Execute);
        join(shutdown);
        assertTrue(executor.isShutdown());
        assertFalse(executor.isTerminated());
        assertEquals(1, harness.control(cpu).activeTaskCount());

        taskRelease.countDown();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS));
        executor.close();
        harness.assertClean();
    }

    /// Proves E4 transition ordering, immutable restart configuration, and epoch rollback.
    @Test
    void e4OrdersRestartShutdownCloseAndEpochOverflow() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        try (PinnedThreadExecutor executor = harness.acquire(cpu, Thread::new)) {
            executor.shutdown();
            executor.start("restarted", 99, true);
            Thread restarted = executor.getPinnedFactory().newThread(() -> {});
            assertEquals("restarted", restarted.getName());
            assertEquals(Thread.MAX_PRIORITY, restarted.getPriority());
            assertTrue(restarted.isDaemon());

            executor.start("ignored-running", Thread.MIN_PRIORITY, false);
            assertEquals(
                    "restarted", executor.getPinnedFactory().newThread(() -> {}).getName());

            executor.shutdown();
            harness.control(cpu).setEpochForTest(Long.MAX_VALUE);
            assertThrows(IllegalStateException.class, () -> executor.start("overflow", Thread.MIN_PRIORITY, false));
            assertTrue(executor.isShutdown());
            assertEquals(
                    "restarted", executor.getPinnedFactory().newThread(() -> {}).getName());

            executor.close();
            assertThrows(IllegalStateException.class, () -> executor.start("closed", Thread.NORM_PRIORITY, false));
        } finally {
            harness.closeAll();
        }
        harness.assertClean();
    }

    /// Proves E5 CLOSED-active tombstone retention and nonoverlapping replacement.
    @Test
    void e5KeepsClosedActiveIdentityAsTombstoneUntilFinalExit() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        PinnedThreadExecutor old = harness.acquire(cpu, Thread::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        old.execute(() -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            entered.countDown();
            awaitIgnoringInterrupt(release);
            active.decrementAndGet();
        });
        assertTrue(entered.await(WAIT_SECONDS, SECONDS));

        old.close();
        assertEquals(1, harness.registry.entryCount());
        assertThrows(RejectedExecutionException.class, () -> harness.acquire(cpu, Thread::new));
        assertFalse(old.isTerminated());

        release.countDown();
        assertTrue(old.awaitTermination(WAIT_SECONDS, SECONDS));
        awaitCondition(() -> harness.registry.entryCount() == 0);
        PinnedThreadExecutor replacement = harness.acquire(cpu, Thread::new);
        assertNotSame(old, replacement);
        replacement.execute(() -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            active.decrementAndGet();
        });
        replacement.shutdown();
        assertTrue(replacement.awaitTermination(WAIT_SECONDS, SECONDS));
        replacement.close();
        assertEquals(1, maximum.get(), "old and replacement tasks must not overlap");
        harness.assertClean();
    }

    /// Proves E6 fresh concurrent identities under the same managed logical CPU.
    @Test
    void e6RunsDistinctFreshThreadsConcurrentlyUnderManagedOwnership() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        PinnedThreadExecutor executor = harness.acquire(cpu, Thread::new);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Set<Thread> taskThreads = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Runnable command = () -> {
            Thread current = Thread.currentThread();
            taskThreads.add(current);
            assertEquals(cpu, harness.bindings.owner(current));
            entered.countDown();
            await(release);
        };
        try {
            executor.execute(command);
            executor.execute(command);
            assertTrue(entered.await(WAIT_SECONDS, SECONDS));
            assertEquals(2, taskThreads.size());
            assertEquals(2, harness.bindings.owners.size());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS));
            executor.close();
        }
        harness.assertClean();
    }

    /// Proves E7 command, recoverable-cleanup, fatal-cleanup, and later-use outcomes.
    @Test
    void e7PreservesCommandFailureAndCompletesRecoverableAndFatalCleanup() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        ConcurrentLinkedQueue<Throwable> uncaught = new ConcurrentLinkedQueue<>();
        CountDownLatch failures = new CountDownLatch(2);
        Function<Runnable, Thread> creator = command -> {
            Thread thread = new Thread(command);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                uncaught.add(failure);
                failures.countDown();
            });
            return thread;
        };
        PinnedThreadExecutor executor = harness.acquire(cpu, creator);
        harness.bindings.releaseRecoverable.set(true);
        harness.bindings.closeRecoverable.set(true);
        CommandFailure commandFailure = new CommandFailure();
        executor.execute(() -> {
            throw commandFailure;
        });
        awaitCondition(() -> uncaught.size() == 1);
        assertSame(commandFailure, uncaught.peek());
        awaitCondition(() -> harness.control(cpu).activeTaskCount() == 0);
        harness.bindings.assertEmpty();

        CountDownLatch laterUse = new CountDownLatch(1);
        executor.execute(laterUse::countDown);
        assertTrue(laterUse.await(WAIT_SECONDS, SECONDS));
        awaitCondition(() -> harness.control(cpu).activeTaskCount() == 0);

        harness.bindings.releaseFatal.set(true);
        executor.execute(() -> {});
        assertTrue(failures.await(WAIT_SECONDS, SECONDS));
        assertEquals(2, uncaught.size());
        assertTrue(uncaught.stream().anyMatch(OutOfMemoryError.class::isInstance));
        awaitCondition(() -> harness.control(cpu).activeTaskCount() == 0);
        executor.close();
        harness.assertClean();
    }

    /// Proves E8 orderly versus interrupting shutdown and truthful active termination.
    @Test
    void e8SeparatesOrderlyShutdownFromBestEffortInterruption() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        PinnedThreadExecutor executor = harness.acquire(cpu, Thread::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
            }
        });
        assertTrue(entered.await(WAIT_SECONDS, SECONDS));

        executor.shutdown();
        assertEquals(1, interrupted.getCount(), "orderly shutdown must not interrupt");
        assertFalse(executor.isTerminated());

        Thread.currentThread().interrupt();
        try {
            List<Runnable> queued = executor.shutdownNow();
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(queued.isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> queued.add(() -> {}));
        } finally {
            Thread.interrupted();
        }
        assertTrue(interrupted.await(WAIT_SECONDS, SECONDS));
        assertFalse(executor.isTerminated());

        release.countDown();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS));
        executor.close();
        harness.assertClean();
    }

    /// Proves E9 deadline, spurious wakeup, restart, completion, and interruption predicates.
    @Test
    void e9MakesAwaitTruthfulSpuriousSafeAndInterruptionPreserving() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        PinnedThreadExecutor executor = harness.acquire(cpu, Thread::new);
        assertFalse(executor.awaitTermination(0, SECONDS));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            await(release);
        });
        assertTrue(entered.await(WAIT_SECONDS, SECONDS));
        executor.shutdown();
        assertFalse(executor.awaitTermination(0, SECONDS));
        assertFalse(executor.awaitTermination(-1, SECONDS));
        assertFalse(executor.awaitTermination(1, MILLISECONDS));

        AtomicReference<Boolean> spuriousResult = new AtomicReference<>();
        Thread spuriousWaiter =
                new Thread(() -> spuriousResult.set(executor.awaitTermination(WAIT_SECONDS, SECONDS)), "e9-spurious");
        spuriousWaiter.start();
        awaitWaiting(spuriousWaiter);
        harness.control(cpu).signalForTest();
        awaitWaiting(spuriousWaiter);
        assertNull(spuriousResult.get());

        AtomicReference<Boolean> restartResult = new AtomicReference<>();
        Thread saturatedWaiter =
                new Thread(() -> restartResult.set(executor.awaitTermination(Long.MAX_VALUE, SECONDS)), "e9-saturated");
        saturatedWaiter.start();
        awaitWaiting(saturatedWaiter);
        executor.start("e9-restarted", Thread.NORM_PRIORITY, true);
        join(saturatedWaiter);
        assertEquals(Boolean.FALSE, restartResult.get());
        join(spuriousWaiter);
        assertEquals(Boolean.FALSE, spuriousResult.get());

        executor.shutdown();
        AtomicReference<Boolean> interruptedResult = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread interruptedWaiter = new Thread(
                () -> {
                    interruptedResult.set(executor.awaitTermination(Long.MAX_VALUE, SECONDS));
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                },
                "e9-interrupted");
        interruptedWaiter.start();
        awaitWaiting(interruptedWaiter);
        interruptedWaiter.interrupt();
        join(interruptedWaiter);
        assertEquals(Boolean.FALSE, interruptedResult.get());
        assertTrue(interruptRestored.get());

        AtomicReference<Boolean> completionResult = new AtomicReference<>();
        Thread completionWaiter = new Thread(
                () -> completionResult.set(executor.awaitTermination(WAIT_SECONDS, SECONDS)), "e9-completion");
        completionWaiter.start();
        awaitWaiting(completionWaiter);
        release.countDown();
        join(completionWaiter);
        assertEquals(Boolean.TRUE, completionResult.get());
        assertTrue(executor.awaitTermination(WAIT_SECONDS, SECONDS));
        executor.close();
        harness.assertClean();
    }

    /// Proves E10 delayed exact cleanup and noncapturing structural reachability.
    @Test
    void e10MakesDelayedCleanupIdentitySafeAndStructurallyNoncapturing() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        PinnedThreadExecutor old = harness.acquire(cpu, Thread::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        old.execute(() -> {
            entered.countDown();
            await(release);
        });
        assertTrue(entered.await(WAIT_SECONDS, SECONDS));
        Runnable oldAction = harness.registry.actionForTest(cpu);
        assertNotNull(oldAction);
        AtomicReference<PinnedThreadExecutor> installed = new AtomicReference<>();
        harness.registry.closeAll(null, () -> {
            release.countDown();
            awaitCondition(() -> harness.registry.entryCount() == 0);
            installed.set(harness.acquire(cpu, Thread::new));
        });
        assertTrue(old.awaitTermination(WAIT_SECONDS, SECONDS));

        PinnedThreadExecutor replacement = installed.get();
        assertNotNull(replacement);
        assertNotSame(old, replacement);
        assertSame(replacement, harness.registry.executorForTest(cpu));
        assertSame(replacement, harness.registry.get(cpu));
        assertNoForbiddenReachability(oldAction);

        replacement.close();
        harness.assertClean();
    }

    /// Proves E11 one-hook lifecycle across entries, restart, repetition, and failures.
    @Test
    void e11BoundsHookLifecycleAcrossEntriesRestartAndRemovalFailures() {
        Harness harness = new Harness();
        int firstCpu = testCpu();
        int secondCpu = alternateCpu(firstCpu);
        PinnedThreadExecutor first = harness.acquire(firstCpu, Thread::new);
        PinnedThreadExecutor second = harness.acquire(secondCpu, Thread::new);
        assertEquals(1, harness.hooks.adds.get());
        assertEquals(1, harness.hooks.maximumLive.get());

        first.shutdown();
        assertSame(first, harness.acquire(firstCpu, Thread::new));
        first.close();
        first.close();
        second.close();
        harness.registry.closeAll();
        harness.registry.closeAll();
        harness.assertClean();
        assertEquals(1, harness.hooks.maximumLive.get());

        Harness retained = new Harness();
        retained.hooks.failRemove.set(true);
        PinnedThreadExecutor old = retained.acquire(firstCpu, Thread::new);
        old.close();
        assertEquals(0, retained.registry.entryCount());
        assertEquals(1, retained.hooks.prepared.get());
        PinnedThreadExecutor replacement = retained.acquire(firstCpu, Thread::new);
        assertEquals(1, retained.hooks.prepared.get(), "failed removal must reuse hook identity");
        replacement.close();
        retained.assertClean();

        Harness shutdown = new Harness();
        shutdown.hooks.shutdownOnRemove.set(true);
        shutdown.acquire(firstCpu, Thread::new).close();
        assertTrue(shutdown.registry.shutdownInProgressForTest());
        assertEquals(1, shutdown.hooks.maximumLive.get());
        shutdown.hooks.shutdownOnRemove.set(false);
        shutdown.acquire(firstCpu, Thread::new).close();
        shutdown.assertClean();
    }

    /// Proves E12 registry-wide close gating and post-exit replacement.
    @Test
    void e12GatesCloseAllAgainstAcquisitionAndPreventsReplacementOverlap() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        PinnedThreadExecutor old = harness.acquire(cpu, Thread::new);
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        old.execute(() -> {
            taskEntered.countDown();
            awaitIgnoringInterrupt(taskRelease);
        });
        assertTrue(taskEntered.await(WAIT_SECONDS, SECONDS));

        CountDownLatch snapshotClosed = new CountDownLatch(1);
        CountDownLatch releaseRegistry = new CountDownLatch(1);
        Thread closeAll = new Thread(
                () -> harness.registry.closeAll(() -> {
                    snapshotClosed.countDown();
                    await(releaseRegistry);
                }),
                "e12-close-all");
        closeAll.start();
        assertTrue(snapshotClosed.await(WAIT_SECONDS, SECONDS));

        AtomicReference<Throwable> acquisition = new AtomicReference<>();
        Thread acquirer = new Thread(
                () -> {
                    try {
                        harness.acquire(cpu, Thread::new);
                    } catch (Throwable failure) {
                        acquisition.set(failure);
                    }
                },
                "e12-acquirer");
        acquirer.start();
        awaitThreadState(acquirer, Thread.State.BLOCKED);
        releaseRegistry.countDown();
        join(closeAll);
        join(acquirer);
        assertInstanceOf(RejectedExecutionException.class, acquisition.get());
        assertFalse(old.isTerminated());

        taskRelease.countDown();
        assertTrue(old.awaitTermination(WAIT_SECONDS, SECONDS));
        awaitCondition(() -> harness.registry.entryCount() == 0);
        PinnedThreadExecutor replacement = harness.acquire(cpu, Thread::new);
        assertNotSame(old, replacement);
        replacement.close();
        harness.assertClean();
    }

    /// Covers direct factory, creator, configuration, start, restart, and API boundaries.
    @Test
    void validatesFactoryCreatorConfigurationStartAndApiBoundaries() throws Exception {
        int cpu = testCpu();
        assertThrows(
                NullPointerException.class,
                () -> PinnedThreadExecutor.getOrSetIfAbsent(null, cpu, "null", Thread.NORM_PRIORITY, false));
        assertThrows(
                NullPointerException.class,
                () -> PinnedThreadExecutor.getOrSetIfAbsent(Thread::new, cpu, null, Thread.NORM_PRIORITY, false));
        assertThrows(IllegalArgumentException.class, () -> PinnedThreadExecutor.get(-1));
        assertThrows(IllegalArgumentException.class, () -> PinnedThreadExecutor.get(SystemInfo.getCpuCount()));
        assertThrows(IllegalArgumentException.class, () -> PinnedThreadExecutor.get(Long.MAX_VALUE));

        Harness direct = new Harness();
        PinnedThreadExecutor executor = direct.acquire(cpu, Thread::new);
        assertThrows(
                NullPointerException.class, () -> executor.getPinnedFactory().newThread(null));
        Thread first = executor.getPinnedFactory().newThread(() -> {});
        Thread second = executor.getPinnedFactory().newThread(() -> {});
        assertNotSame(first, second);
        assertEquals(Thread.State.NEW, first.getState());
        assertEquals(0, direct.control(cpu).activeTaskCount());
        executor.close();
        CountDownLatch directRan = new CountDownLatch(1);
        Thread afterClose = executor.getPinnedFactory().newThread(directRan::countDown);
        afterClose.start();
        join(afterClose);
        assertTrue(directRan.await(WAIT_SECONDS, SECONDS));
        assertEquals(
                0, direct.controlOrNull(cpu) == null ? 0 : direct.control(cpu).activeTaskCount());
        direct.assertClean();

        Harness nullCreator = new Harness();
        PinnedThreadExecutor returnsNull = nullCreator.acquire(cpu, ignored -> null);
        assertThrows(RejectedExecutionException.class, () -> returnsNull.execute(() -> {}));
        assertEquals(0, nullCreator.control(cpu).activeTaskCount());
        returnsNull.close();
        nullCreator.assertClean();

        Harness nonNew = new Harness();
        PinnedThreadExecutor returnsRunning = nonNew.acquire(cpu, ignored -> Thread.currentThread());
        assertThrows(RejectedExecutionException.class, () -> returnsRunning.execute(() -> {}));
        returnsRunning.close();
        nonNew.assertClean();

        Harness throwingCreator = new Harness();
        IllegalArgumentException creatorFailure = new IllegalArgumentException("creator");
        PinnedThreadExecutor creatorThrows = throwingCreator.acquire(cpu, ignored -> {
            throw creatorFailure;
        });
        assertSame(creatorFailure, assertThrows(IllegalArgumentException.class, () -> creatorThrows.execute(() -> {})));
        creatorThrows.close();
        throwingCreator.assertClean();

        IllegalStateException configurationFailure = new IllegalStateException("configure");
        FakeCleanupRegistrar cleanups = new FakeCleanupRegistrar();
        FakeHookRegistrar hooks = new FakeHookRegistrar();
        FakeTaskBinding bindings = new FakeTaskBinding();
        PinnedThreadExecutor.Registry configurationRegistry =
                PinnedThreadExecutor.newTestRegistry(cleanups, hooks, bindings, (thread, name, priority, daemon) -> {
                    throw configurationFailure;
                });
        PinnedThreadExecutor configurationThrows =
                configurationRegistry.acquire(Thread::new, cpu, "configuration", Thread.NORM_PRIORITY, false);
        assertSame(
                configurationFailure,
                assertThrows(IllegalStateException.class, () -> configurationThrows.execute(() -> {})));
        assertEquals(0, configurationRegistry.controlForTest(cpu).activeTaskCount());
        configurationThrows.close();
        assertEquals(0, cleanups.live.get());
        assertEquals(0, hooks.live.get());

        Harness startFailure = new Harness();
        IllegalStateException startException = new IllegalStateException("start");
        PinnedThreadExecutor cannotStart = startFailure.acquire(cpu, command -> new Thread(command) {
            /// Injects a deterministic candidate-start failure before a task becomes active.
            @Override
            public synchronized void start() {
                throw startException;
            }
        });
        assertSame(startException, assertThrows(IllegalStateException.class, () -> cannotStart.execute(() -> {})));
        assertEquals(0, startFailure.control(cpu).activeTaskCount());
        cannotStart.close();
        startFailure.assertClean();

        Harness retainedCreator = new Harness();
        AtomicInteger originalCreations = new AtomicInteger();
        AtomicInteger replacementCreations = new AtomicInteger();
        PinnedThreadExecutor retained = retainedCreator.acquire(cpu, command -> {
            originalCreations.incrementAndGet();
            return new Thread(command);
        });
        retained.shutdown();
        assertSame(
                retained,
                retainedCreator.registry.acquire(
                        command -> {
                            replacementCreations.incrementAndGet();
                            return new Thread(command);
                        },
                        cpu,
                        "restart",
                        Thread.NORM_PRIORITY,
                        false));
        CountDownLatch ran = new CountDownLatch(1);
        retained.execute(ran::countDown);
        assertTrue(ran.await(WAIT_SECONDS, SECONDS));
        awaitCondition(() -> retainedCreator.control(cpu).activeTaskCount() == 0);
        assertEquals(1, originalCreations.get());
        assertEquals(0, replacementCreations.get());
        retained.close();
        retainedCreator.assertClean();
    }

    /// Proves cleaner/hook registration failures roll back before publication.
    @Test
    void rollsBackCleanerAndHookRegistrationFailuresWithoutPublication() {
        int cpu = testCpu();
        FakeCleanupRegistrar failedCleanup = new FakeCleanupRegistrar();
        failedCleanup.failRegistration.set(true);
        FakeHookRegistrar cleanupHooks = new FakeHookRegistrar();
        PinnedThreadExecutor.Registry cleanupRegistry =
                PinnedThreadExecutor.newTestRegistry(failedCleanup, cleanupHooks, new FakeTaskBinding());
        assertThrows(
                IllegalStateException.class,
                () -> cleanupRegistry.acquire(Thread::new, cpu, "cleanup-failure", Thread.NORM_PRIORITY, false));
        assertEquals(0, cleanupRegistry.entryCount());
        assertEquals(0, cleanupHooks.adds.get());

        FakeCleanupRegistrar hookCleanups = new FakeCleanupRegistrar();
        FakeHookRegistrar failedHook = new FakeHookRegistrar();
        failedHook.failAdd.set(true);
        PinnedThreadExecutor.Registry hookRegistry =
                PinnedThreadExecutor.newTestRegistry(hookCleanups, failedHook, new FakeTaskBinding());
        assertThrows(
                IllegalStateException.class,
                () -> hookRegistry.acquire(Thread::new, cpu, "hook-failure", Thread.NORM_PRIORITY, false));
        assertEquals(0, hookRegistry.entryCount());
        assertEquals(0, hookCleanups.live.get());
        assertEquals(0, failedHook.live.get());
    }

    /// Runs 50 bounded alternating execute/shutdown and close/acquire race rounds.
    @Test
    @Timeout(30)
    void boundedStressLeavesNoTasksTombstonesRegistrationsHooksOrBindings() throws Exception {
        Harness harness = new Harness();
        int cpu = testCpu();
        for (int round = 0; round < 50; round++) {
            if ((round & 1) == 0) {
                stressExecuteAgainstShutdown(harness, cpu, round);
            } else {
                stressCloseAgainstAcquire(harness, cpu, round);
            }
            assertEquals(0, harness.registry.entryCount(), "registry round " + round);
            assertEquals(0, harness.cleanups.live.get(), "cleanups round " + round);
            assertEquals(0, harness.hooks.live.get(), "hooks round " + round);
            harness.bindings.assertEmpty();
        }
        assertTrue(harness.hooks.maximumLive.get() <= 1);
    }

    /// Supplies one allocation-free boolean cleanup predicate to bounded test polling.
    @FunctionalInterface
    private interface BooleanSupplier {

        /// Returns the current predicate observation.
        boolean getAsBoolean();
    }

    /// Bundles one isolated registry and its bounded deterministic fakes.
    private static final class Harness {

        private final FakeCleanupRegistrar cleanups = new FakeCleanupRegistrar();
        private final FakeHookRegistrar hooks = new FakeHookRegistrar();
        private final FakeTaskBinding bindings = new FakeTaskBinding();
        private final PinnedThreadExecutor.Registry registry =
                PinnedThreadExecutor.newTestRegistry(this.cleanups, this.hooks, this.bindings);

        /// Acquires from the isolated registry with stable test configuration.
        private PinnedThreadExecutor acquire(int cpu, Function<Runnable, ? extends Thread> creator) {
            return this.registry.acquire(creator, cpu, "lifecycle-" + cpu, Thread.NORM_PRIORITY, true);
        }

        /// Returns the required mapped control for direct predicate assertions.
        private PinnedThreadExecutor.LifecycleControl control(int cpu) {
            PinnedThreadExecutor.LifecycleControl control = this.registry.controlForTest(cpu);
            assertNotNull(control);
            return control;
        }

        /// Returns the mapped control or null after exact cleanup.
        private PinnedThreadExecutor.LifecycleControl controlOrNull(int cpu) {
            return this.registry.controlForTest(cpu);
        }

        /// Closes this isolated registry without touching production state.
        private void closeAll() {
            this.registry.closeAll();
        }

        /// Asserts every deterministic task, registry, hook, cleanable, and binding count is zero.
        private void assertClean() {
            assertEquals(0, this.registry.entryCount(), "registry entries");
            assertEquals(0, this.cleanups.live.get(), "cleanup registrations");
            assertEquals(0, this.hooks.live.get(), "runtime hooks");
            this.bindings.assertEmpty();
        }
    }

    /// Captures nonreferent cleanup actions and exact live-registration counts.
    private static final class FakeCleanupRegistrar implements PinnedThreadExecutor.CleanupRegistrar {

        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger live = new AtomicInteger();
        private final AtomicBoolean failRegistration = new AtomicBoolean();

        /// Registers one action without retaining the supplied referent.
        @Override
        public PinnedThreadExecutor.CleanupRegistration register(Object referent, Runnable action) {
            if (this.failRegistration.get()) {
                throw new IllegalStateException("cleanup registration failure");
            }
            this.registrations.incrementAndGet();
            this.live.incrementAndGet();
            return new FakeCleanup(action, this.live);
        }
    }

    /// Runs one fake cleanup action at most once and decrements its live count.
    private static final class FakeCleanup implements PinnedThreadExecutor.CleanupRegistration {

        private final Runnable action;
        private final AtomicInteger live;
        private final AtomicBoolean cleaned = new AtomicBoolean();

        /// Creates one action-only fake cleanup registration.
        private FakeCleanup(Runnable action, AtomicInteger live) {
            this.action = action;
            this.live = live;
        }

        /// Claims and runs this fake registration at most once.
        @Override
        public void clean() {
            if (this.cleaned.compareAndSet(false, true)) {
                this.live.decrementAndGet();
                this.action.run();
            }
        }
    }

    /// Prepares noncapturing fake hook identities with deterministic failure controls.
    private static final class FakeHookRegistrar implements PinnedThreadExecutor.HookRegistrar {

        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger adds = new AtomicInteger();
        private final AtomicInteger removes = new AtomicInteger();
        private final AtomicInteger live = new AtomicInteger();
        private final AtomicInteger maximumLive = new AtomicInteger();
        private final AtomicBoolean failAdd = new AtomicBoolean();
        private final AtomicBoolean failRemove = new AtomicBoolean();
        private final AtomicBoolean shutdownOnRemove = new AtomicBoolean();

        /// Prepares one fake hook identity without retaining the unused action.
        @Override
        public PinnedThreadExecutor.HookRegistration prepare(Runnable action) {
            this.prepared.incrementAndGet();
            return new FakeHookRegistration(
                    this.adds,
                    this.removes,
                    this.live,
                    this.maximumLive,
                    this.failAdd,
                    this.failRemove,
                    this.shutdownOnRemove);
        }
    }

    /// Counts add/remove operations for one exact fake hook identity.
    private static final class FakeHookRegistration implements PinnedThreadExecutor.HookRegistration {

        private final AtomicInteger adds;
        private final AtomicInteger removes;
        private final AtomicInteger live;
        private final AtomicInteger maximumLive;
        private final AtomicBoolean failAdd;
        private final AtomicBoolean failRemove;
        private final AtomicBoolean shutdownOnRemove;
        private final AtomicBoolean added = new AtomicBoolean();

        /// Creates one fake identity sharing registrar counters and failure controls.
        private FakeHookRegistration(
                AtomicInteger adds,
                AtomicInteger removes,
                AtomicInteger live,
                AtomicInteger maximumLive,
                AtomicBoolean failAdd,
                AtomicBoolean failRemove,
                AtomicBoolean shutdownOnRemove) {
            this.adds = adds;
            this.removes = removes;
            this.live = live;
            this.maximumLive = maximumLive;
            this.failAdd = failAdd;
            this.failRemove = failRemove;
            this.shutdownOnRemove = shutdownOnRemove;
        }

        /// Adds this exact fake identity or raises the injected add failure.
        @Override
        public void add() {
            if (this.failAdd.get()) {
                throw new IllegalStateException("hook add failure");
            }
            if (this.added.compareAndSet(false, true)) {
                this.adds.incrementAndGet();
                int count = this.live.incrementAndGet();
                this.maximumLive.accumulateAndGet(count, Math::max);
            }
        }

        /// Removes this exact fake identity or reports the injected failure mode.
        @Override
        public boolean remove() {
            this.removes.incrementAndGet();
            if (this.shutdownOnRemove.get()) {
                throw new IllegalStateException("JVM shutdown");
            }
            if (this.failRemove.compareAndSet(true, false)) {
                return false;
            }
            if (this.added.compareAndSet(true, false)) {
                this.live.decrementAndGet();
                return true;
            }
            return false;
        }
    }

    /// Mirrors managed-owner and affinity-lease state without asserting physical placement.
    private static final class FakeTaskBinding implements PinnedThreadExecutor.TaskBinding {

        private final ConcurrentHashMap<Thread, Integer> owners = new ConcurrentHashMap<>();
        private final Set<Thread> leases = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean releaseRecoverable = new AtomicBoolean();
        private final AtomicBoolean closeRecoverable = new AtomicBoolean();
        private final AtomicBoolean releaseFatal = new AtomicBoolean();

        /// Installs one managed logical owner and returns its cleanup token.
        @Override
        public ThreadTools.ManagedCpuBinding bind(int cpu) {
            Thread owner = Thread.currentThread();
            assertNull(this.owners.put(owner, cpu));
            return () -> {
                this.owners.remove(owner);
                if (this.closeRecoverable.compareAndSet(true, false)) {
                    throw new IllegalStateException("recoverable owner close");
                }
            };
        }

        /// Records one affinity lease only after managed ownership is visible.
        @Override
        public boolean setAffinity(int cpu) {
            Thread owner = Thread.currentThread();
            assertEquals(cpu, this.owners.get(owner));
            this.leases.add(owner);
            return false;
        }

        /// Removes the affinity lease before raising any injected cleanup failure.
        @Override
        public void releaseAffinity() {
            Thread owner = Thread.currentThread();
            this.leases.remove(owner);
            if (this.releaseFatal.compareAndSet(true, false)) {
                throw new OutOfMemoryError("fatal affinity release");
            }
            if (this.releaseRecoverable.compareAndSet(true, false)) {
                throw new IllegalStateException("recoverable affinity release");
            }
        }

        /// Returns the managed logical owner observed for one task thread.
        private Integer owner(Thread thread) {
            return this.owners.get(thread);
        }

        /// Asserts no participating thread retains managed-owner or affinity-lease state.
        private void assertEmpty() {
            assertTrue(this.owners.isEmpty(), "managed owners: " + this.owners);
            assertTrue(this.leases.isEmpty(), "affinity leases: " + this.leases);
        }
    }

    /// Distinguishes the exact original command failure at the uncaught handler.
    private static final class CommandFailure extends RuntimeException {}
}
