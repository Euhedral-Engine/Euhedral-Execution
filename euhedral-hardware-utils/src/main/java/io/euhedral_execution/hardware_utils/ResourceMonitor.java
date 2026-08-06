package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.internal.monitor.DeadlineWaiter;
import io.euhedral_execution.hardware_utils.internal.monitor.LatestValueDispatcher;
import io.euhedral_execution.hardware_utils.internal.monitor.MonotonicClock;
import io.euhedral_execution.hardware_utils.internal.monitor.TopologyUpdater;
import io.euhedral_execution.hardware_utils.internal.pressure.PressureEvaluation;
import io.euhedral_execution.hardware_utils.internal.pressure.PressureEvaluator;

import io.euhedral_execution.hardware_utils.internal.pressure.PressureState;
import io.euhedral_execution.hardware_utils.internal.sampling.DetailedSystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.internal.sampling.SampleStateEngine;
import io.euhedral_execution.hardware_utils.internal.sampling.SystemSnapshotCompatibilityAdapter;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.IntervalHardwareSample;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.ThreadFactory;

public class ResourceMonitor implements AutoCloseable {

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
        this(mapper, sampleRate, SystemInfo.SNAPSHOTTER);
    }

    ResourceMonitor(TopologyMapper mapper, Duration sampleRate, SystemSnapshotProvider provider) {
        this(TopologyUpdater.from(mapper), sampleRate, provider, 
             System::nanoTime, DeadlineWaiter.DEFAULT, Thread::new);
    }

    ResourceMonitor(TopologyUpdater topology, Duration sampleRate, SystemSnapshotProvider provider,
                    MonotonicClock clock, DeadlineWaiter waiter, ThreadFactory threadFactory) {
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
            int s = (int) STATE.getAcquire(this);
            if (s == CLOSING || s == CLOSED) return;
            if (s == RUNNING || s == STARTING) return;
            
            if (STATE.compareAndSet(this, s, STARTING)) {
                if (s == NEW) {
                    Thread t = threadFactory.newThread(this::runLoop);
                    t.setDaemon(true);
                    this.pollingThread = t;
                    t.start();
                } else if (s == STOPPED) {
                    // Signal thread to resume
                    coalescedReadPending = false;
                    STATE.setRelease(this, RUNNING);
                }
                return;
            }
        }
    }

    public void stop() {
        while (true) {
            int s = (int) STATE.getAcquire(this);
            if (s == CLOSING || s == CLOSED || s == STOPPED || s == NEW) return;
            if (STATE.compareAndSet(this, s, STOPPED)) {
                coalescedReadPending = true;
                return;
            }
        }
    }

    @Override
    public void close() {
        while (true) {
            int s = (int) STATE.getAcquire(this);
            if (s == CLOSING || s == CLOSED) {
                dispatcher.awaitClosed();
                return;
            }
            if (STATE.compareAndSet(this, s, CLOSING)) {
                dispatcher.beginClose(this::cleanup);
                
                Thread t = pollingThread;
                if (t != null && t != Thread.currentThread()) {
                    t.interrupt();
                }
                
                // wait for eval & pub to finish
                while ((boolean) EVAL_ACTIVE.getAcquire(this) || (boolean) PUB_CLAIMED.getAcquire(this)) {
                    Thread.onSpinWait();
                }
                
                STATE.setRelease(this, CLOSED);
                dispatcher.awaitClosed();
                return;
            }
        }
    }

    public void addListener(MonitorListener listener) {
        dispatcher.addListener(listener);
    }
    
    public void removeListener(MonitorListener listener) {
        dispatcher.removeListener(listener);
    }

    private void cleanup() {
        // All temporary allocations, executor hooks, and listener arrays clear on CLOSED.
        // dispatcher already handles listeners.
    }

    private void runLoop() {
        try {
            STATE.compareAndSet(this, STARTING, RUNNING);
            long t0 = clock.nanoTime();
            long nextTick = t0;
            long lastNow = t0;

            while (true) {
                int s = (int) STATE.getAcquire(this);
                if (s == CLOSING || s == CLOSED) break;
                
                if (s == STOPPED) {
                    if (coalescedReadPending) {
                        evaluateAndPublish();
                        coalescedReadPending = false;
                    }
                    Thread.onSpinWait();
                    continue;
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

                waiter.await(nextTick, clock);
                
                // re-check state after wait
                s = (int) STATE.getAcquire(this);
                if (s == CLOSING || s == CLOSED) break;
                if (s == STOPPED) continue;

                evaluateAndPublish();
                lastNow = clock.nanoTime();
            }
        } catch (Throwable t) {
            // Safe fallback to CLOSING then CLOSED
            close();
        }
    }

    private void evaluateAndPublish() {
        EVAL_ACTIVE.setRelease(this, true);
        try {
            int s = (int) STATE.getAcquire(this);
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
                
                PUB_CLAIMED.setRelease(this, true);
                try {
                    s = (int) STATE.getAcquire(this);
                    if (s != CLOSING && s != CLOSED) {
                        topology.update(util);
                        dispatcher.offer(util);
                    }
                } finally {
                    PUB_CLAIMED.setRelease(this, false);
                }
            }
        } finally {
            EVAL_ACTIVE.setRelease(this, false);
        }
    }
    
    public final HardwareUtilization getUtilization() {
        return lastUtilization;
    }

    @FunctionalInterface
    public interface MonitorListener {
        void update(HardwareUtilization utilization);
    }
}
