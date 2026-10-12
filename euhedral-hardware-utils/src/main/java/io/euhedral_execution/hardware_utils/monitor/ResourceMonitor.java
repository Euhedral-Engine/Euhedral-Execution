package io.euhedral_execution.hardware_utils.monitor;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.monitor.internal.DeadlineWaiter;
import io.euhedral_execution.hardware_utils.monitor.internal.LatestValueDispatcher;
import io.euhedral_execution.hardware_utils.monitor.internal.MonotonicClock;
import io.euhedral_execution.hardware_utils.monitor.internal.SampleStateEngine;
import io.euhedral_execution.hardware_utils.monitor.internal.SystemSnapshotCompatibilityAdapter;
import io.euhedral_execution.hardware_utils.monitor.internal.TopologyUpdater;
import io.euhedral_execution.hardware_utils.monitor.internal.pressure.PressureEvaluation;
import io.euhedral_execution.hardware_utils.monitor.internal.pressure.PressureEvaluator;
import io.euhedral_execution.hardware_utils.monitor.internal.pressure.PressureState;
import io.euhedral_execution.hardware_utils.monitor.sampling.DetailedSystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.topology.TopologyMapper;
import io.euhedral_execution.hardware_utils.util.internal.Constants;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourceMonitor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(ResourceMonitor.class));

    private static final int NEW = 0;
    private static final int STARTING = 1;
    private static final int RUNNING = 2;
    private static final int STOPPED = 3;
    private static final int CLOSING = 4;
    private static final int CLOSED = 5;

    private static final VarHandle STATE;
    private static final VarHandle EVAL_ACTIVE;
    private static final VarHandle PUB_CLAIMED;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(ResourceMonitor.class, "state", int.class);
            EVAL_ACTIVE = l.findVarHandle(ResourceMonitor.class, "evaluationActive", boolean.class);
            PUB_CLAIMED = l.findVarHandle(ResourceMonitor.class, "publicationClaimed", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile int state = NEW;
    private volatile boolean evaluationActive;
    private volatile boolean publicationClaimed;

    private final TopologyUpdater topology;
    private final DetailedSystemSnapshotProvider provider;
    private final long sampleRateNs;
    private final MonotonicClock clock;
    private final DeadlineWaiter waiter;
    private final ThreadFactory threadFactory;
    private final Object evaluationLock = new Object();

    private final LatestValueDispatcher dispatcher;
    private final SampleStateEngine stateEngine;

    private volatile PressureState pressureState;
    private volatile Thread pollingThread;
    private volatile HardwareUtilization lastUtilization;

    // For coalesced stopped read
    private volatile boolean coalescedReadPending = false;

    public ResourceMonitor(TopologyMapper mapper) {
        this(mapper, Duration.ofMillis(200));
    }

    public ResourceMonitor(TopologyMapper mapper, Duration sampleRate) {
        this(mapper, sampleRate, requireSnapshotter(SystemInfo.SNAPSHOTTER));
    }

    static SystemSnapshotProvider requireSnapshotter(@Nullable SystemSnapshotProvider snapshotter) {
        if (snapshotter == null) {
            throw new IllegalStateException("resource sampling unavailable on " + System.getProperty("os.name"));
        }
        return snapshotter;
    }

    ResourceMonitor(TopologyMapper mapper, Duration sampleRate, SystemSnapshotProvider provider) {
        this(TopologyUpdater.from(mapper), sampleRate, provider, System::nanoTime, DeadlineWaiter.DEFAULT, Thread::new);
    }

    ResourceMonitor(
            TopologyUpdater topology,
            Duration sampleRate,
            SystemSnapshotProvider provider,
            MonotonicClock clock,
            DeadlineWaiter waiter,
            ThreadFactory threadFactory) {
        try {
            if (sampleRate == null || sampleRate.toNanos() <= 0) {
                throw new IllegalArgumentException("Invalid sample rate");
            }
            if (provider == null || topology == null) {
                throw new NullPointerException();
            }
            this.topology = topology;
            this.sampleRateNs = sampleRate.toNanos();
            this.clock = clock;
            this.waiter = waiter;
            this.threadFactory = threadFactory;
            this.provider = SystemSnapshotCompatibilityAdapter.wrap(provider);
            this.dispatcher = new LatestValueDispatcher();

            // Initialization: do not sample in constructor.
            int cpuCount = SystemInfo.getCpuCount();
            this.stateEngine = new SampleStateEngine(cpuCount, this.sampleRateNs);
            this.pressureState = new PressureState(cpuCount);

        } catch (Throwable t) {
            this.state = CLOSED;
            throw t;
        }
    }

    public void start() {
        while (true) {
            int s = (int) STATE.getVolatile(this);
            if (s == CLOSING || s == CLOSED) return;
            if (s == RUNNING || s == STARTING) return;

            if (STATE.compareAndSet(this, s, STARTING)) {
                if (s == NEW) {
                    try {
                        Thread t = threadFactory.newThread(this::runLoop);
                        if (t == null) {
                            throw new IllegalStateException("ThreadFactory returned null");
                        }
                        t.setDaemon(true);
                        this.pollingThread = t;
                        t.start();
                    } catch (Throwable failure) {
                        this.pollingThread = null;
                        // Roll back so a later start() can retry. Concurrent stop()/start() calls may have
                        // moved STARTING to STOPPED or RUNNING meanwhile; no thread exists, so all of
                        // those return to NEW.
                        // If close() won the race, finish the shutdown ourselves: no thread will.
                        while (true) {
                            int cur = (int) STATE.getVolatile(this);
                            if (cur == STARTING || cur == STOPPED || cur == RUNNING) {
                                if (STATE.compareAndSet(this, cur, NEW)) break;
                            } else {
                                tryFinishClose();
                                break;
                            }
                        }
                        throw failure;
                    }
                } else {
                    // STOPPED -> STARTING -> RUNNING. A CAS (not a plain store) so a concurrent
                    // close() that moved STARTING to CLOSING is never overwritten.
                    STATE.compareAndSet(this, STARTING, RUNNING);
                    // State CAS happens before the unpark; the polling thread re-reads state after waking.
                    LockSupport.unpark(pollingThread);
                }
                return;
            }
        }
    }

    public void stop() {
        while (true) {
            int s = (int) STATE.getVolatile(this);
            if (s == CLOSING || s == CLOSED || s == STOPPED || s == NEW) return;
            // Published before the state CAS so a polling thread that observes STOPPED also observes the
            // pending coalesced read. The polling thread clears it before consuming it.
            coalescedReadPending = true;
            if (STATE.compareAndSet(this, s, STOPPED)) {
                return;
            }
        }
    }

    /// Closes the monitor and waits until shutdown is complete.
    ///
    /// If called from the polling thread, from a listener callback (the dispatcher thread), or from a
    /// topology update running inside this monitor's evaluation, the call cannot wait for itself: it
    /// starts the shutdown and returns immediately, and shutdown completes asynchronously once the
    /// callback returns.
    @Override
    public void close() {
        closeInternal(Long.MAX_VALUE);
    }

    /// Closes the monitor, waiting at most `timeout` for shutdown to complete.
    ///
    /// @return true if the monitor is fully closed on return; false if the timeout elapsed first. When
    ///     called from the polling thread, a listener callback, or a topology update inside this
    ///     monitor's evaluation, the call never waits (it would wait on itself): it starts the shutdown
    ///     and returns false unless the monitor is already fully closed.
    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long nanos;
        try {
            nanos = Math.max(0L, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        return closeInternal(nanos);
    }

    private boolean closeInternal(long timeoutNs) {
        while (true) {
            int s = (int) STATE.getVolatile(this);
            if (s == CLOSING || s == CLOSED || STATE.compareAndSet(this, s, CLOSING)) {
                if (s != CLOSING && s != CLOSED) {
                    dispatcher.beginClose(this::tryFinishClose);
                }
                Thread t = pollingThread;
                if (t != null) {
                    if (t != Thread.currentThread()) {
                        t.interrupt();
                    }
                    LockSupport.unpark(t);
                }
                break;
            }
        }

        Thread current = Thread.currentThread();
        if (current == pollingThread || dispatcher.isDispatchThread() || Thread.holdsLock(evaluationLock)) {
            tryFinishClose();
            return isFullyClosed();
        }

        // Backoff wait; the caller's interrupt status must not turn parkNanos into a busy loop.
        boolean interrupted = Thread.interrupted();
        try {
            long start = System.nanoTime();
            int spins = 0;
            while (true) {
                tryFinishClose();
                if (isFullyClosed()) {
                    return true;
                }
                if (timeoutNs != Long.MAX_VALUE && System.nanoTime() - start >= timeoutNs) {
                    return false;
                }
                if (spins < 64) {
                    spins++;
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(50_000L);
                }
            }
        } finally {
            if (interrupted) {
                current.interrupt();
            }
        }
    }

    private boolean isFullyClosed() {
        return (int) STATE.getVolatile(this) == CLOSED && dispatcher.isClosed();
    }

    /// Moves CLOSING to CLOSED once no evaluation or publication is in flight. Callable from any
    /// thread, any number of times.
    ///
    /// Safe because of the Dekker handshake documented on [#evaluateAndPublish()]: close() stores
    /// CLOSING (volatile) before this method loads the flags (volatile), while an evaluator stores a flag
    /// (volatile) before loading the state (volatile). Sequential consistency over those four accesses
    /// guarantees that either this method sees the flag set (and does not publish CLOSED), or the
    /// evaluator sees CLOSING and backs out without touching shared output. The flag clears form the
    /// mirror pair: an evaluator stores flag = false (volatile) and then calls this method, which loads
    /// the state; a closer CASes CLOSING and then loads the flags. One of the two always observes the
    /// other, so the last party to act completes the CLOSING -> CLOSED transition.
    private void tryFinishClose() {
        if ((int) STATE.getVolatile(this) != CLOSING) {
            return;
        }
        if ((boolean) EVAL_ACTIVE.getVolatile(this) || (boolean) PUB_CLAIMED.getVolatile(this)) {
            return;
        }
        STATE.compareAndSet(this, CLOSING, CLOSED);
    }

    public void addListener(MonitorListener listener) {
        dispatcher.addListener(listener);
    }

    public void removeListener(MonitorListener listener) {
        dispatcher.removeListener(listener);
    }

    private void runLoop() {
        try {
            STATE.compareAndSet(this, STARTING, RUNNING);
            long t0;
            while (true) {
                try {
                    t0 = clock.nanoTime();
                    break;
                } catch (RuntimeException e) {
                    recordTickFailure(e);
                    int cs = (int) STATE.getVolatile(this);
                    if (cs == CLOSING || cs == CLOSED) return;
                    backoffAfterFailure();
                }
            }
            long nextTick = t0;
            long lastNow = t0;

            while (true) {
                try {
                    int s = (int) STATE.getVolatile(this);
                    if (s == CLOSING || s == CLOSED) break;

                    if (s == STOPPED) {
                        // Clear before consuming so a stop() that re-arms it concurrently is never lost.
                        if (coalescedReadPending) {
                            coalescedReadPending = false;
                            tick();
                            continue;
                        }
                        // stop()/start()/close() CAS the state before unparking us and we re-read the state
                        // on every wakeup, so neither a lost nor a spurious wakeup can strand this thread.
                        LockSupport.park(this);
                        continue;
                    }
                    if (s == STARTING) {
                        STATE.compareAndSet(this, STARTING, RUNNING);
                    }

                    long now = clock.nanoTime();
                    if (now < lastNow) {
                        // regression, reanchor
                        t0 = now;
                        nextTick = now;
                    }
                    lastNow = now;

                    if (now >= nextTick) {
                        if (nextTick != t0) { // skip first iteration overrun logic
                            long skips = (now - t0) / sampleRateNs;
                            nextTick = t0 + (skips + 1) * sampleRateNs;
                        } else {
                            nextTick = t0 + sampleRateNs;
                        }
                    }

                    try {
                        waiter.await(nextTick, clock);
                    } catch (RuntimeException e) {
                        recordTickFailure(e);
                        backoffAfterFailure();
                        continue;
                    }

                    // re-check state after wait
                    s = (int) STATE.getVolatile(this);
                    if (s == CLOSING || s == CLOSED) break;
                    if (s == STOPPED) continue;

                    tick();
                    lastNow = clock.nanoTime();
                } catch (RuntimeException e) {
                    // e.g. an injected clock; the poller must outlive it
                    recordTickFailure(e);
                    backoffAfterFailure();
                }
            }
        } catch (Error e) {
            LOGGER.error("ResourceMonitor polling thread terminating on error", e);
            closeInternal(0L);
        } finally {
            // Complete the CLOSING -> CLOSED transition if close() is not (or no longer) waiting.
            tryFinishClose();
        }
    }

    /// Runs one evaluation; a RuntimeException from the provider, pressure model or topology update is
    /// recorded and the monitor keeps running. Errors propagate to the loop's termination path.
    private void tick() {
        try {
            evaluateAndPublish();
        } catch (RuntimeException e) {
            recordTickFailure(e);
        }
    }

    /// A failing waiter or clock would otherwise turn the loop into a busy spin. close() interrupts and
    /// unparks the polling thread, so this never delays shutdown.
    private void backoffAfterFailure() {
        LockSupport.parkNanos(this, sampleRateNs);
    }

    // Owned by the polling thread; no synchronization needed.
    private long tickFailures;

    private void recordTickFailure(RuntimeException e) {
        if (tickFailures++ == 0) {
            LOGGER.warn("ResourceMonitor tick failed; continuing with the next tick", e);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("ResourceMonitor tick failed again (failure #{}); continuing", tickFailures, e);
        }
    }

    /// Evaluates one sample and publishes it, unless the monitor is closing.
    ///
    /// Shutdown handshake (Dekker-style, needs sequential consistency): an evaluator does
    /// `store(flag = true); load(state)` and a closer does `store(state = CLOSING); load(flag)`. Each side
    /// stores to one location and then loads from the other, so release/acquire is not enough: it allows
    /// the store to be reordered after the later load, and both sides could then miss each other. All four
    /// accesses use volatile (seq-cst) mode, which places them in one total order, so at least one side
    /// observes the other: either the closer sees the flag and waits (CLOSED is not published while the
    /// evaluation is in flight), or the evaluator sees CLOSING/CLOSED and returns before touching any
    /// shared output. The clears are volatile too: the last evaluator does `store(flag = false);
    /// load(state)` to finish a pending close, while the closer does `CAS(state = CLOSING); load(flag)`,
    /// a second Dekker pair. With release stores both could miss each other and leave the monitor in
    /// CLOSING forever.
    private void evaluateAndPublish() {
        synchronized (evaluationLock) {
            EVAL_ACTIVE.setVolatile(this, true);
            try {
                int s = (int) STATE.getVolatile(this);
                if (s == CLOSING || s == CLOSED) return;

                long pollStartNs = clock.nanoTime();
                if (stateEngine.isSlowDue(pollStartNs)) {
                    stateEngine.processSlow(pollStartNs, provider.sampleSlow(pollStartNs));
                }
                FastHardwareSample fast = provider.sampleFast(pollStartNs);
                long evaluationNs = clock.nanoTime();

                IntervalHardwareSample interval = stateEngine.processFast(evaluationNs, fast);
                if (interval != null) {
                    PressureEvaluation eval = PressureEvaluator.evaluate(interval, pressureState, evaluationNs);
                    pressureState = eval.state();
                    HardwareUtilization util = eval.candidate();
                    this.lastUtilization = util;

                    PUB_CLAIMED.setVolatile(this, true);
                    try {
                        s = (int) STATE.getVolatile(this);
                        if (s != CLOSING && s != CLOSED) {
                            topology.update(util);
                            if (s == RUNNING) {
                                dispatcher.offer(util);
                            }
                        }
                    } finally {
                        PUB_CLAIMED.setVolatile(this, false);
                    }
                }
            } finally {
                EVAL_ACTIVE.setVolatile(this, false);
                // A close() whose waiter timed out or is the dispatcher/polling thread cannot finish the
                // transition itself, so the last evaluator does it.
                tryFinishClose();
            }
        }
    }

    public final HardwareUtilization getUtilization() {
        HardwareUtilization utilization = lastUtilization;
        if (utilization != null) {
            return utilization;
        }

        int currentState = (int) STATE.getVolatile(this);
        if (currentState == NEW || currentState == STOPPED) {
            evaluateAndPublish();
            utilization = lastUtilization;
        }
        if (utilization == null) {
            throw new IllegalStateException("Resource utilization is not available");
        }
        return utilization;
    }

    @FunctionalInterface
    public interface MonitorListener {
        void update(HardwareUtilization utilization);
    }
}
