package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.internal.Constants;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
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

/// An automatic cpu pinning executor that creates one fresh thread for every accepted command.
public final class PinnedThreadExecutor extends AbstractExecutorService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(PinnedThreadExecutor.class));
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Registry REGISTRY =
            new Registry(new JdkCleanupRegistrar(), new JvmHookRegistrar(), new ThreadToolsTaskBinding());
    private final Function<Runnable, ? extends Thread> threadCreator;
    private final LifecycleControl control;
    private final Registry registry;
    private final EntryIdentity entryIdentity;
    private final CleanupSlot cleanupSlot;
    private final TaskBinding taskBinding;
    private final ThreadConfigurator threadConfigurator;
    @Getter
    private final ThreadFactory pinnedFactory;
    @Getter
    private final int cpu;
    /// Constructs an executor whose registry identity is not published until cleanup is installed.
    private PinnedThreadExecutor(
            Function<Runnable, ? extends Thread> threadCreator,
            int cpu,
            LifecycleControl control,
            Registry registry,
            EntryIdentity entryIdentity,
            CleanupSlot cleanupSlot,
            TaskBinding taskBinding,
            ThreadConfigurator threadConfigurator) {
        this.threadCreator = threadCreator;
        this.cpu = cpu;
        this.control = control;
        this.registry = registry;
        this.entryIdentity = entryIdentity;
        this.cleanupSlot = cleanupSlot;
        this.taskBinding = taskBinding;
        this.threadConfigurator = threadConfigurator;
        this.pinnedFactory = new PinnedFactory(this);
    }

    /// Acquires the one live executor for a logical CPU, creating it with ordinary threads.
    public static PinnedThreadExecutor getOrSetIfAbsent(long cpu, String name, int priority, boolean daemon) {
        return getOrSetIfAbsent(Thread::new, cpu, name, priority, daemon);
    }

    /// Acquires the one live executor for a logical CPU with a creator fixed to a new identity.
    public static PinnedThreadExecutor getOrSetIfAbsent(
            Function<Runnable, ? extends Thread> threadCreator, long cpu, String name, int priority, boolean daemon) {
        Objects.requireNonNull(threadCreator, "threadCreator");
        Configuration configuration = Configuration.create(name, priority, daemon);
        int validatedCpu = validateCpu(cpu);
        return REGISTRY.acquire(threadCreator, cpu, validatedCpu, configuration);
    }

    /// Returns the currently running executor for a logical CPU without restarting it.
    public static PinnedThreadExecutor get(long cpu) {
        validateCpu(cpu);
        return REGISTRY.get(cpu);
    }

    /// Permanently closes every running executor.
    public static void closeAll() {
        REGISTRY.closeAll();
    }

    /// Validates a public long CPU key before any narrowing or registry access.
    private static int validateCpu(long cpu) {
        int cpuCount = SystemInfo.getCpuCount();
        if (cpu < 0 || cpu >= cpuCount) {
            throw new IllegalArgumentException("Logical CPU is outside the supported span: " + cpu);
        }
        return (int) cpu;
    }

    /// Performs best-effort interruption and unparking without clearing caller interruption.
    private static void interruptAll(List<Thread> threads) {
        for (Thread thread : threads) {
            try {
                thread.interrupt();
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.debug("Failed to interrupt pinned task {}", thread, failure);
            }
            try {
                LockSupport.unpark(thread);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.debug("Failed to unpark pinned task {}", thread, failure);
            }
        }
    }

    /// Provides package-local isolated lifecycle state for deterministic P3-B tests.
    static Registry newTestRegistry(
            CleanupRegistrar cleanupRegistrar, HookRegistrar hookRegistrar, TaskBinding taskBinding) {
        return new Registry(cleanupRegistrar, hookRegistrar, taskBinding);
    }

    /// Provides an isolated registry with an injected deterministic configuration-failure seam.
    static Registry newTestRegistry(
            CleanupRegistrar cleanupRegistrar,
            HookRegistrar hookRegistrar,
            TaskBinding taskBinding,
            ThreadConfigurator threadConfigurator) {
        return new Registry(cleanupRegistrar, hookRegistrar, taskBinding, threadConfigurator);
    }

    /// Restarts a shutdown executor with new thread properties; a running executor is unchanged.
    public void start(String name, int priority, boolean daemon) {
        Configuration candidate = Configuration.create(name, priority, daemon);
        this.control.start(candidate);
    }

    /// Transitions to shutdown and best-effort interrupts every accepted active task.
    @Override
    public @NonNull List<Runnable> shutdownNow() {
        interruptAll(this.control.shutdownNow());
        return Collections.emptyList();
    }

    /// Creates and starts one pinned thread.
    @Override
    public void execute(@NonNull Runnable command) {
        Objects.requireNonNull(command, "command");
        ExecutionSnapshot snapshot = this.control.snapshotForExecution();
        Thread candidate = createThread(command, snapshot.configuration(), true);
        this.control.registerAndStart(candidate, snapshot.epoch());
    }

    /// Begins orderly shutdown without interrupting already accepted work.
    @Override
    public void shutdown() {
        this.control.shutdown();
    }

    /// Reports whether the executor is currently shutdown or permanently closed.
    @Override
    public boolean isShutdown() {
        return this.control.isShutdown();
    }

    /// Permanently closes this identity through its one cleaner registration.
    @Override
    public void close() {
        this.cleanupSlot.clean();
    }

    /// Reports the instantaneous non-running-and-empty termination status.
    @Override
    public boolean isTerminated() {
        return this.control.isTerminated();
    }

    /// Waits on for the executor to terminate.
    @Override
    public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        return this.control.awaitTermination(timeout, unit);
    }

    /// Creates and configures a new ownership/affinity wrapper outside lifecycle monitors.
    private Thread createThread(Runnable command, Configuration configuration, boolean tracked) {
        Runnable wrapped = () -> runCommand(command, tracked);
        Thread candidate = this.threadCreator.apply(wrapped);
        if (candidate == null || candidate.getState() != Thread.State.NEW) {
            throw new RejectedExecutionException("Thread creator must return a NEW thread");
        }
        this.threadConfigurator.configure(
                candidate, configuration.name(), configuration.priority(), configuration.daemon());
        if (candidate.getState() != Thread.State.NEW) {
            throw new RejectedExecutionException("Thread creator must return a NEW thread");
        }
        return candidate;
    }

    /// Runs one command inside the managed binding and always removes tracked membership.
    private void runCommand(Runnable command, boolean tracked) {
        try {
            ThreadTools.ManagedCpuBinding binding = this.taskBinding.bind(this.cpu);
            try {
                this.taskBinding.setAffinity(this.cpu);
                command.run();
            } finally {
                try {
                    releaseAffinityRecoverably();
                } finally {
                    closeBindingRecoverably(binding);
                }
            }
        } finally {
            if (tracked) {
                taskFinished(Thread.currentThread());
            }
        }
    }

    /// Logs recoverable affinity-release failures while allowing fatal errors to propagate.
    private void releaseAffinityRecoverably() {
        try {
            this.taskBinding.releaseAffinity();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("Failed to release affinity for logical CPU {}", this.cpu, failure);
        }
    }

    /// Logs recoverable managed-owner cleanup failures while preserving outer task cleanup.
    private void closeBindingRecoverably(ThreadTools.ManagedCpuBinding binding) {
        try {
            binding.close();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("Failed to close managed binding for logical CPU {}", this.cpu, failure);
        }
    }

    /// Removes one active thread and drops a closed tombstone only after its last task exits.
    private void taskFinished(Thread thread) {
        if (this.control.removeTask(thread)) {
            this.registry.removeExact(this.entryIdentity);
        }
    }

    /// The executable lifecycle states for one registry identity.
    private enum State {
        RUNNING,
        SHUTDOWN,
        CLOSED
    }

    /// Abstracts the one per-entry cleaner registration for deterministic tests.
    interface CleanupRegistrar {

        /// Registers a noncapturing action against a weakly observed object.
        CleanupRegistration register(Object referent, Runnable action);
    }

    /// Represents one idempotent per-entry cleanup registration.
    interface CleanupRegistration {

        /// Deregisters and invokes the exact cleanup action once.
        void clean();
    }

    /// Abstracts preparation of the one registry-wide runtime hook.
    interface HookRegistrar {

        /// Prepares a hook identity without adding it yet.
        HookRegistration prepare(Runnable action);
    }

    /// Represents one runtime-hook thread and its registration operations.
    interface HookRegistration {

        /// Adds the prepared hook.
        void add();

        /// Removes the exact hook and reports whether removal succeeded.
        boolean remove();
    }

    /// Abstracts affinity operations for deterministic task tests.
    interface TaskBinding {

        /// Opens a managed logical-CPU scope on the calling task thread.
        ThreadTools.ManagedCpuBinding bind(int cpu);

        /// Attempts affinity after managed ownership is installed.
        boolean setAffinity(int cpu);

        /// Attempts release before managed ownership closes.
        void releaseAffinity();
    }

    /// Abstracts candidate configuration only to force the documented failure boundary in tests.
    interface ThreadConfigurator {

        /// Applies one immutable configuration to a candidate NEW thread.
        void configure(Thread thread, String name, int priority, boolean daemon);
    }

    /// One immutable, validated thread-property publication.
    private record Configuration(String name, int priority, boolean daemon) {

        /// Validates and clamps one immutable thread configuration.
        private static Configuration create(String name, int priority, boolean daemon) {
            Objects.requireNonNull(name, "name");
            int clamped = Math.max(Thread.MIN_PRIORITY, Math.min(priority, Thread.MAX_PRIORITY));
            return new Configuration(name, clamped, daemon);
        }
    }

    /// One coherent execute-side configuration and epoch observation.
    private record ExecutionSnapshot(Configuration configuration, long epoch) {}

    /// One close-all entry and the active task snapshot captured while gated.
    private record CloseRequest(RegistryEntry entry, List<Thread> threads) {}

    /// Holds all coherent per-executor lifecycle state behind one monitor.
    static final class LifecycleControl {

        private final IdentityHashMap<Thread, Boolean> activeTasks = new IdentityHashMap<>();
        private State state = State.RUNNING;
        private Configuration configuration;
        private long epoch = 1;

        /// Creates one initially running lifecycle at epoch one.
        LifecycleControl(Configuration configuration) {
            this.configuration = configuration;
        }

        /// Returns a coherent configuration/epoch snapshot or rejects non-running execution.
        synchronized ExecutionSnapshot snapshotForExecution() {
            if (this.state != State.RUNNING) {
                throw new RejectedExecutionException("Executor is not running");
            }
            return new ExecutionSnapshot(this.configuration, this.epoch);
        }

        /// Returns the latest configuration without consulting lifecycle state.
        synchronized Configuration configuration() {
            return this.configuration;
        }

        /// Registers and starts a candidate atomically with respect to shutdown and restart.
        synchronized void registerAndStart(Thread candidate, long expectedEpoch) {
            if (this.state != State.RUNNING
                    || this.epoch != expectedEpoch
                    || candidate.getState() != Thread.State.NEW) {
                throw new RejectedExecutionException("Executor changed before task acceptance");
            }
            this.activeTasks.put(candidate, Boolean.TRUE);
            try {
                candidate.start();
            } catch (RuntimeException | Error failure) {
                this.activeTasks.remove(candidate);
                notifyAll();
                throw failure;
            }
        }

        /// Applies the frozen RUNNING/SHUTDOWN/CLOSED restart table.
        synchronized void start(Configuration candidate) {
            if (this.state == State.RUNNING) {
                return;
            }
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("A closed executor cannot restart");
            }
            if (this.epoch == Long.MAX_VALUE) {
                throw new IllegalStateException("Executor lifecycle epoch overflow");
            }
            this.configuration = candidate;
            this.epoch++;
            this.state = State.RUNNING;
            notifyAll();
        }

        /// Performs orderly RUNNING-to-SHUTDOWN transition without task interruption.
        synchronized void shutdown() {
            if (this.state == State.RUNNING) {
                this.state = State.SHUTDOWN;
                notifyAll();
            }
        }

        /// Performs shutdown when needed and returns an exact active-task snapshot.
        synchronized List<Thread> shutdownNow() {
            if (this.state == State.RUNNING) {
                this.state = State.SHUTDOWN;
                notifyAll();
            }
            return new ArrayList<>(this.activeTasks.keySet());
        }

        /// Permanently closes this lifecycle and returns an exact active-task snapshot.
        synchronized List<Thread> close() {
            if (this.state != State.CLOSED) {
                this.state = State.CLOSED;
                notifyAll();
            }
            return new ArrayList<>(this.activeTasks.keySet());
        }

        /// Removes only the exact current task and reports an empty CLOSED tombstone.
        synchronized boolean removeTask(Thread thread) {
            this.activeTasks.remove(thread);
            notifyAll();
            return this.state == State.CLOSED && this.activeTasks.isEmpty();
        }

        /// Reports whether replacement must remain blocked by an active CLOSED identity.
        synchronized boolean isClosedWithActiveTasks() {
            return this.state == State.CLOSED && !this.activeTasks.isEmpty();
        }

        /// Reports whether this control has no active task identities.
        synchronized boolean isEmpty() {
            return this.activeTasks.isEmpty();
        }

        /// Returns the lifecycle state for registry decisions under the fixed lock order.
        synchronized State state() {
            return this.state;
        }

        /// Reports the public shutdown predicate.
        synchronized boolean isShutdown() {
            return this.state != State.RUNNING;
        }

        /// Reports the public instantaneous termination predicate.
        synchronized boolean isTerminated() {
            return this.state != State.RUNNING && this.activeTasks.isEmpty();
        }

        /// Waits with elapsed subtraction and restores interruption instead of throwing it.
        synchronized boolean awaitTermination(long timeout, TimeUnit unit) {
            long budget = Math.max(0, unit.toNanos(timeout));
            if (this.state == State.RUNNING) {
                return false;
            }
            if (this.activeTasks.isEmpty()) {
                return true;
            }
            if (budget == 0) {
                return false;
            }

            long started = System.nanoTime();
            long remaining = budget;
            while (true) {
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                try {
                    wait(millis, nanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (this.state == State.RUNNING) {
                    return false;
                }
                if (this.activeTasks.isEmpty()) {
                    return true;
                }
                long elapsed = System.nanoTime() - started;
                if (elapsed >= budget) {
                    return false;
                }
                remaining = budget - elapsed;
            }
        }

        /// Wakes termination waiters to exercise spurious-notification predicate handling.
        synchronized void signalForTest() {
            notifyAll();
        }

        /// Sets a deterministic epoch for the frozen overflow boundary test.
        synchronized void setEpochForTest(long epoch) {
            this.epoch = epoch;
        }

        /// Returns the active task count for deterministic cleanup assertions.
        synchronized int activeTaskCount() {
            return this.activeTasks.size();
        }
    }

    /// Owns the low-frequency singleton map, hook identity, and close-all gate behind one monitor.
    static final class Registry {

        private final Map<Long, RegistryEntry> entries = new HashMap<>();
        private final CleanupRegistrar cleanupRegistrar;
        private final HookRegistrar hookRegistrar;
        private final TaskBinding taskBinding;
        private final ThreadConfigurator threadConfigurator;
        private HookRegistration hook;
        private boolean shutdownInProgress;

        /// Creates an isolated registry from bounded cleanup, hook, and task-binding roles.
        Registry(CleanupRegistrar cleanupRegistrar, HookRegistrar hookRegistrar, TaskBinding taskBinding) {
            this(cleanupRegistrar, hookRegistrar, taskBinding, new JdkThreadConfigurator());
        }

        /// Creates an isolated registry with an explicit candidate-configuration operation.
        Registry(
                CleanupRegistrar cleanupRegistrar,
                HookRegistrar hookRegistrar,
                TaskBinding taskBinding,
                ThreadConfigurator threadConfigurator) {
            this.cleanupRegistrar = Objects.requireNonNull(cleanupRegistrar, "cleanupRegistrar");
            this.hookRegistrar = Objects.requireNonNull(hookRegistrar, "hookRegistrar");
            this.taskBinding = Objects.requireNonNull(taskBinding, "taskBinding");
            this.threadConfigurator = Objects.requireNonNull(threadConfigurator, "threadConfigurator");
        }

        /// Acquires through the public validation boundary while retaining this isolated registry.
        PinnedThreadExecutor acquire(
                Function<Runnable, ? extends Thread> threadCreator,
                long cpu,
                String name,
                int priority,
                boolean daemon) {
            Objects.requireNonNull(threadCreator, "threadCreator");
            Configuration configuration = Configuration.create(name, priority, daemon);
            int validatedCpu = validateCpu(cpu);
            return acquire(threadCreator, cpu, validatedCpu, configuration);
        }

        /// Acquires, restarts, or installs the exact identity for one validated CPU key.
        synchronized PinnedThreadExecutor acquire(
                Function<Runnable, ? extends Thread> threadCreator, long cpuKey, int cpu, Configuration configuration) {
            RegistryEntry current = this.entries.get(cpuKey);
            if (current != null) {
                PinnedThreadExecutor live = current.executor().get();
                State state = current.control().state();
                if (live != null && state == State.RUNNING) {
                    return live;
                }
                if (live != null && state == State.SHUTDOWN) {
                    live.control.start(configuration);
                    return live;
                }
                if (!current.control().isEmpty()) {
                    throw new RejectedExecutionException("A closed executor still owns active tasks for CPU " + cpuKey);
                }
                removeStale(current);
            }

            EntryIdentity identity = new EntryIdentity(this, cpuKey);
            LifecycleControl control = new LifecycleControl(configuration);
            CleanupSlot cleanupSlot = new CleanupSlot();
            PinnedThreadExecutor executor = new PinnedThreadExecutor(
                    threadCreator,
                    cpu,
                    control,
                    this,
                    identity,
                    cleanupSlot,
                    this.taskBinding,
                    this.threadConfigurator);

            HookRegistration selectedHook = this.hook;
            boolean addHook = selectedHook == null;
            if (addHook) {
                selectedHook =
                        Objects.requireNonNull(this.hookRegistrar.prepare(new HookTask(this)), "hookRegistration");
            }

            CleanupAction action = new CleanupAction(identity, control, selectedHook);
            CleanupRegistration cleanup =
                    Objects.requireNonNull(this.cleanupRegistrar.register(executor, action), "cleanupRegistration");
            cleanupSlot.initialize(cleanup);

            if (addHook) {
                try {
                    selectedHook.add();
                    this.hook = selectedHook;
                } catch (RuntimeException | Error failure) {
                    try {
                        cleanup.clean();
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }

            RegistryEntry entry =
                    new RegistryEntry(identity, new WeakReference<>(executor), control, action, cleanup, selectedHook);
            this.entries.put(cpuKey, entry);
            return executor;
        }

        /// Observes only a live RUNNING identity and performs safe stale cleanup when possible.
        synchronized PinnedThreadExecutor get(long cpuKey) {
            RegistryEntry entry = this.entries.get(cpuKey);
            if (entry == null) {
                return null;
            }
            PinnedThreadExecutor live = entry.executor().get();
            if (live != null && entry.control().state() == State.RUNNING) {
                return live;
            }
            if ((live == null || entry.control().state() == State.CLOSED)
                    && entry.control().isEmpty()) {
                removeStale(entry);
            }
            return null;
        }

        /// Marks one bounded registry snapshot CLOSED before allowing later acquisition.
        void closeAll() {
            closeAll(null, null);
        }

        /// Runs an optional deterministic barrier after the snapshot closes while still gated.
        void closeAll(Runnable afterClosedWhileLocked) {
            closeAll(afterClosedWhileLocked, null);
        }

        /// Runs deterministic barriers around release of the close-all registry gate.
        void closeAll(Runnable afterClosedWhileLocked, Runnable beforeCleanupAfterRelease) {
            List<CloseRequest> requests;
            synchronized (this) {
                requests = new ArrayList<>(this.entries.size());
                for (RegistryEntry entry : this.entries.values()) {
                    requests.add(new CloseRequest(entry, entry.control().close()));
                }
                if (afterClosedWhileLocked != null) {
                    afterClosedWhileLocked.run();
                }
            }
            if (beforeCleanupAfterRelease != null) {
                beforeCleanupAfterRelease.run();
            }
            for (CloseRequest request : requests) {
                interruptAll(request.threads());
                request.entry().cleanup().clean();
            }
        }

        /// Removes only the mapped exact entry after proving its lifecycle has no active task.
        synchronized void removeExact(EntryIdentity identity) {
            RegistryEntry current = this.entries.get(identity.cpuKey());
            if (current == null
                    || current.identity() != identity
                    || !current.control().isEmpty()) {
                return;
            }
            this.entries.remove(identity.cpuKey());
            removeHookIfOrdinarilyEmpty(current.hook());
        }

        /// Runs stale cleanup ownership before allowing a replacement identity to publish.
        private void removeStale(RegistryEntry entry) {
            entry.cleanup().clean();
            removeExact(entry.identity());
        }

        /// Removes the one exact hook on an ordinary empty transition or retains it on failure.
        private void removeHookIfOrdinarilyEmpty(HookRegistration expectedHook) {
            if (!this.entries.isEmpty() || this.hook != expectedHook || this.hook == null) {
                return;
            }
            try {
                if (this.hook.remove()) {
                    this.hook = null;
                }
            } catch (IllegalStateException shutdown) {
                this.shutdownInProgress = true;
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.debug("Failed to remove PinnedThreadExecutor shutdown hook", failure);
            }
        }

        /// Returns the isolated registry size for deterministic assertions.
        synchronized int entryCount() {
            return this.entries.size();
        }

        /// Returns the exact mapped executor regardless of lifecycle for race assertions.
        synchronized PinnedThreadExecutor executorForTest(long cpuKey) {
            RegistryEntry entry = this.entries.get(cpuKey);
            return entry == null ? null : entry.executor().get();
        }

        /// Returns the exact mapped cleanup action for delayed-action assertions.
        synchronized Runnable actionForTest(long cpuKey) {
            RegistryEntry entry = this.entries.get(cpuKey);
            return entry == null ? null : entry.action();
        }

        /// Returns the mapped lifecycle control for deterministic state-boundary assertions.
        synchronized LifecycleControl controlForTest(long cpuKey) {
            RegistryEntry entry = this.entries.get(cpuKey);
            return entry == null ? null : entry.control();
        }

        /// Reports whether hook removal observed JVM shutdown.
        synchronized boolean shutdownInProgressForTest() {
            return this.shutdownInProgress;
        }
    }

    /// One fully initialized weak executor entry published under the registry monitor.
    private record RegistryEntry(
            EntryIdentity identity,
            WeakReference<PinnedThreadExecutor> executor,
            LifecycleControl control,
            CleanupAction action,
            CleanupRegistration cleanup,
            HookRegistration hook) {}

    /// Gives every registry entry a stable object identity and its owning isolated registry.
    private record EntryIdentity(Registry registry, long cpuKey) {}

    /// Claims cleanup once without retaining an executor, factory, command, or active wrapper.
    private static final class CleanupAction implements Runnable {

        private final EntryIdentity identity;
        private final LifecycleControl control;

        @SuppressWarnings("unused")
        private final HookRegistration hook;

        private final AtomicBoolean claimed = new AtomicBoolean();

        /// Creates one noncapturing cleanup action for an exact registry identity.
        private CleanupAction(EntryIdentity identity, LifecycleControl control, HookRegistration hook) {
            this.identity = identity;
            this.control = control;
            this.hook = hook;
        }

        /// Permanently closes, interrupts, and exact-removes this identity at most once.
        @Override
        public void run() {
            if (!this.claimed.compareAndSet(false, true)) {
                return;
            }
            List<Thread> tasks = this.control.close();
            interruptAll(tasks);
            if (tasks.isEmpty()) {
                this.identity.registry().removeExact(this.identity);
            }
        }
    }

    /// Holds the registration assigned during construction and before registry publication.
    private static final class CleanupSlot {

        private CleanupRegistration registration;

        /// Assigns the one registration before the executor is published.
        private void initialize(CleanupRegistration registration) {
            if (this.registration != null) {
                throw new IllegalStateException("Cleanup registration already initialized");
            }
            this.registration = registration;
        }

        /// Invokes the idempotent registration action.
        private void clean() {
            CleanupRegistration current = this.registration;
            if (current == null) {
                throw new IllegalStateException("Cleanup registration is not initialized");
            }
            current.clean();
        }
    }

    /// Constructs direct pinned-factory threads without lifecycle membership or automatic start.
    private static final class PinnedFactory implements ThreadFactory {

        private final PinnedThreadExecutor executor;

        /// Creates the public factory surface for one executor identity.
        private PinnedFactory(PinnedThreadExecutor executor) {
            this.executor = executor;
        }

        /// Returns one configured NEW wrapper using the latest published configuration.
        @Override
        public Thread newThread(Runnable command) {
            Objects.requireNonNull(command, "command");
            Configuration configuration = this.executor.control.configuration();
            return this.executor.createThread(command, configuration, false);
        }
    }

    /// Dispatches JVM hook cleanup to one registry without capturing an executor.
    private static final class HookTask implements Runnable {

        private final Registry registry;

        /// Creates one registry-only hook task.
        private HookTask(Registry registry) {
            this.registry = registry;
        }

        /// Closes the registry snapshot during JVM shutdown.
        @Override
        public void run() {
            this.registry.closeAll();
        }
    }

    /// Registers cleanup through the class-wide JDK Cleaner.
    private static final class JdkCleanupRegistrar implements CleanupRegistrar {

        /// Creates one JDK cleanable without retaining its referent in the action.
        @Override
        public CleanupRegistration register(Object referent, Runnable action) {
            Cleaner.Cleanable cleanable = CLEANER.register(referent, action);
            return cleanable::clean;
        }
    }

    /// Adds and removes the JVM shutdown-hook thread.
    private static final class JvmHookRegistrar implements HookRegistrar {

        /// Prepares a new shutdown-hook thread around a registry-only task.
        @Override
        public HookRegistration prepare(Runnable action) {
            return new JvmHookRegistration(
                    new Thread(action, PinnedThreadExecutor.class.getSimpleName() + "-shutdown"));
        }
    }

    /// Wraps the Runtime hook APIs for exactly one hook thread.
    private static final class JvmHookRegistration implements HookRegistration {

        private final Thread thread;

        /// Creates one runtime registration identity.
        private JvmHookRegistration(Thread thread) {
            this.thread = thread;
        }

        /// Registers the thread with the JVM runtime.
        @Override
        public void add() {
            Runtime.getRuntime().addShutdownHook(this.thread);
        }

        /// Removes the thread from the JVM runtime.
        @Override
        public boolean remove() {
            return Runtime.getRuntime().removeShutdownHook(this.thread);
        }
    }

    /// Delegates task ownership and affinity to the ThreadTools bridge.
    private static final class ThreadToolsTaskBinding implements TaskBinding {

        /// Opens the logical-CPU binding.
        @Override
        public ThreadTools.ManagedCpuBinding bind(int cpu) {
            return ThreadTools.bindManagedCpu(cpu);
        }

        /// Attempts existing public affinity application without claiming physical placement.
        @Override
        public boolean setAffinity(int cpu) {
            return ThreadTools.setAffinity(cpu);
        }

        /// Attempts existing public affinity release.
        @Override
        public void releaseAffinity() {
            ThreadTools.releaseAffinity();
        }
    }

    /// Applies ordinary JDK thread properties outside every executor monitor.
    private static final class JdkThreadConfigurator implements ThreadConfigurator {

        /// Applies name, clamped priority, and daemon configuration to one candidate.
        @Override
        public void configure(Thread thread, String name, int priority, boolean daemon) {
            thread.setName(name);
            thread.setPriority(priority);
            thread.setDaemon(daemon);
        }
    }
}
