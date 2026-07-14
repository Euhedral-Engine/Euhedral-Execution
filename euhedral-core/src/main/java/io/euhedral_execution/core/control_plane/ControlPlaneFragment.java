package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.clampLong;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.flow_control.LatticeHotSource;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.metrics.ExecutionMetrics;
import io.euhedral_execution.core.utils.FlowRecorder;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The core of Euhedral Core
///
/// `ControlPlaneFragment` is the control loop that sits between ingress and execution. It
/// continuously tunes batch sizes, dispatch rate, and idle behavior based on how the system is
/// behaving.
///
public final class ControlPlaneFragment extends WorkRequester {

    private static final VarHandle DRAIN;
    private static final VarHandle SNAPSHOT;

    static {
        try {
            DRAIN = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "drainMode", boolean.class);
            SNAPSHOT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "coreSnapshot", CoreSnapshot.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public final int socket;
    public final int core;
    public final int cpu;
    public final boolean isPCore;

    private final Logger logger;
    private final ExecutionMetrics metrics;

    @Getter
    private final FragmentConfig config;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final PinnedThreadExecutor mainExecutor;
    private final CycleState state;

    final LatticeHotSource outputStream;
    boolean drainMode = false;
    CoreSnapshot coreSnapshot = null;

    private volatile Thread mainThread;

    public ControlPlaneFragment(@NonNull FragmentConfig config) {
        super(config.cacheConfig());
        this.config = config;

        if (config.cloneConfig() == null) {
            this.socket = -1;
            this.core = -1;
            this.cpu = -1;
            this.logger = LoggerFactory.getLogger(ControlPlaneFragment.class);
            this.state = null;
            this.mainExecutor = null;
            this.isPCore = false;
            this.metrics = null;
            this.outputStream = null;
        } else {
            String name = config.cloneConfig().shardName() + "-Worker-" + config.cloneConfig().coreId();
            this.logger = LoggerFactory.getLogger(name);

            int[] cpus = config.cloneConfig().getCpuSet();
            this.cpu = cpus[0];

            CpuInfo info = SystemInfo.getCpuInfo(this.cpu);
            this.socket = info.socket();
            this.core = info.core();

            this.state = new CycleState();

            this.mainExecutor =
                    PinnedThreadExecutor.getOrSetIfAbsent(this.cpu, name, Thread.MAX_PRIORITY,
                            false);

            CpuCacheLayout layout = SystemInfo.getCacheLayout(this.cpu);
            this.isPCore =
                    SystemInfo.getCoreInfo(SystemInfo.getCpuInfo(layout.cpu()).core()).pCore();

            this.outputStream = new LatticeHotSource();

            this.metrics = new ExecutionMetrics(config);
        }
    }

    @Override
    protected void accept(AbstractFrame frame) {
        this.outputStream.accept(frame);
    }

    @Override
    public LatticeSource output() {
        return this.outputStream;
    }

    @Override
    public boolean isStarted() {
        return this.running.getAcquire();
    }

    @Override
    public void start() {
        if (this.mainExecutor == null) {
            throw new IllegalStateException(
                    "Pinned Executor has not been set. To start this class, it needs to be instantiated with a CloneConfig.");
        }
        if (this.running.compareAndSet(false, true)) {
            if (this.mainExecutor.isShutdown()) {
                this.mainExecutor.start(this.logger.getName(), Thread.MAX_PRIORITY, false);
            }

            this.mainExecutor.execute(() -> {
                this.mainThread = Thread.currentThread();

                CpuInfo origin = ThreadTools.getCpuInfo();
                if (this.core != origin.core()) {
                    this.logger.warn(
                            "Attempted to pin to Core: {} CPU: {} but was assigned: {}",
                            this.core, this.cpu, origin);
                } else {
                    this.logger.debug("Pinned to Core {} CPU {} P-Core: {}", this.core, this.cpu, this.isPCore);
                }
                ThreadTools.setTimerResolution(1);
                super.register();

                cycle();
                super.removeThread();
            });
        }
    }

    private void cycle() {
        try {
            FlowThread.FlowContext context = FlowThread.getContext();
            Objects.requireNonNull(context);
            context.upstream = getThreadUpstreamQueue();

            double throughput = 0.0;
            while (keepRunning()) {
                long newUpCount = context.upstream.getCachedUpCount();
                if (this.state.upstreamCount != newUpCount && newUpCount > 0) {
                    this.state.upstreamCount = newUpCount;
                    GlobalState.resetThroughput(this.socket, this.cpu);
                } else if (this.state.upstreamCount != newUpCount
                        || this.state.upstreamCount == 0) {
                    this.state.upstreamCount = 0;
                    this.state.reset();
                    throughput = 0;
                    this.state.upstreamCount = idleSpin(context);
                }

                FlowRecorder batchEfficiency = this.state.batchEfficiency;
                FlowRecorder requestEfficiency = this.state.requestEfficiency;
                batchEfficiency.reset();
                requestEfficiency.reset();

                long now = System.nanoTime();

                long processed = execute(context, batchEfficiency);
                long requested = requestSpin(context, requestEfficiency);

                long totalWork = processed + requested;
                long totalWorkTime = System.nanoTime() - now;
                double instantT = totalWork / (double) totalWorkTime;

                throughput =
                        throughput == 0 ? instantT : MathFunctions.ewma(throughput, instantT, 0.10);
                if (!(boolean) DRAIN.getOpaque(this)) {
                    breakoutSpin(this.cpu, throughput);
                }
            }
        } catch (Throwable e) {
            this.logger.error("[CRITICAL] Terminal error encountered in the main loop. Exiting.",
                    e);
        } finally {
            this.running.set(false);
        }
    }

    private long execute(FlowThread.FlowContext context, FlowRecorder batchEfficiency) {
        long total = 0;
        while (keepRunning()) {
            while (keepRunning()) {
                requestAndPull(context, this.state.batchSize);

                long cacheCount = super.getLocalCacheCount();
                if (cacheCount == 0) {
                    this.state.batchEfficiency.record(0);
                    break;
                }

                long limit = this.state.batchSize - this.state.completed;
                limit = Math.min(limit, cacheCount);
                this.metrics.addInProgress(limit);

                double efficiency = (double) limit / this.state.batchSize;

                long start = System.nanoTime();
                this.state.completed += super.drain(limit);
                long end = System.nanoTime();

                this.metrics.addInProgress(-limit);
                this.state.throughputRecorder.record(end, (end - start) / limit);
                this.state.batchEfficiency.record(end, Math.round((efficiency) * 1_000));

                if (this.state.completed >= this.state.batchSize) {
                    this.state.completed = 0;
                    updateLimits(end);
                }
                Thread.onSpinWait();
            }

            if (batchEfficiency.averageUnits() >= 600 || super.getLocalCacheCount() != 0) {
                Thread.onSpinWait();
                continue;
            }

            double measurements = batchEfficiency.getEffectiveMeasurementWindowCount(
                    batchEfficiency.getLastRecordingTime());
            if (measurements > 1) {
                break;
            }
            Thread.onSpinWait();
        }

        return total;
    }

    private void updateLimits(long nowNs) {
        this.state.batchRecorder.record(nowNs, this.state.batchSize);
        if (this.state.batchStart == 0) {
            this.state.batchStart = nowNs;
            return;
        }

        if (this.config.registry() != null) {
            double throughput = this.state.batchRecorder.averageUnitsOverTime();
            this.metrics.reportThroughput(throughput);

            double latency = 1.0 / throughput;
            if (Double.isFinite(latency)) {
                this.metrics.reportLatency(Math.round(latency));
            }
        }

        long currentBatch = this.state.batchSize;

        FlowRecorder recorder = this.state.throughputRecorder;

        double avgThroughput = recorder.averageUnitsOverTime();

        double diffUpper = recorder.getMaxUoT() - avgThroughput;
        double diffLower = avgThroughput - recorder.getMinUoT();

        double averageBatch = Math.round(this.state.batchRecorder.averageUnits());
        if (currentBatch == averageBatch) {
            if (avgThroughput < 0.90) {
                currentBatch += Math.round(Math.max(Math.sqrt(currentBatch), 1));
            } else if (avgThroughput > 1.0) {
                currentBatch -= Math.round(Math.max(Math.sqrt(currentBatch), 1));
            } else if (diffLower < diffUpper) {
                currentBatch++;
            } else {
                currentBatch--;
            }
        }

        currentBatch = MathFunctions.clampLong(currentBatch, 2, this.config.maxBatchSize());
        currentBatch = Math.min(currentBatch, getBatchLimit());

        this.state.batchStart = nowNs;
        this.state.batchSize = currentBatch;
    }

    private long getBatchLimit() {
        CoreSnapshot coreSnapshot = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[this.cpu];

        double pressure = cpuSnapshot.pressure();
        pressure *= this.isPCore ? 0.5 : 0.7;
        long adaptiveCap = Math.round(this.config.maxBatchSize() * (1.0 - pressure));

        return clampLong(adaptiveCap, 2, super.getFrameQuota());
    }

    private long requestSpin(FlowThread.FlowContext context, FlowRecorder requestEfficiency) {
        long requested = 0;
        while (keepRunning()) {
            super.request(context);
            long satisfied = context.satisfiedRequest;
            requested += satisfied;

            double efficiency = satisfied / (double) Math.max(context.originalRequest, 1);
            efficiency = MathFunctions.clampDouble(efficiency, 0.0, 1.0);
            requestEfficiency.record(Math.round(efficiency * 1_000));

            long now = requestEfficiency.getLastRecordingTime();
            double measurements = requestEfficiency.getEffectiveMeasurementWindowCount(now);
            if (requestEfficiency.averageUnits() < 600 && measurements >= 10) {
                break;
            }
            LockSupport.parkNanos(50_000);
        }
        return requested;
    }

    private void breakoutSpin(int cpu, double throughput) {
        GlobalState.setThroughput(this.socket, cpu, throughput);

        if(this.isPCore) {
            Thread.yield();
            return;
        }

        double globalAvgT = GlobalState.meanThroughput(this.socket);
        if (throughput < globalAvgT) {
            logger.trace("Backoff Spin");
            LockSupport.parkNanos(15_000);
            return;
        }
        Thread.yield();
    }

    private long idleSpin(FlowThread.FlowContext threadContext) {
        while (keepRunning()) {
            long upCount = threadContext.upstream.getCachedUpCount();
            if (upCount > 0) {
                return upCount;
            }
            LockSupport.parkNanos(20_000L);
        }
        return 0;
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        SNAPSHOT.setOpaque(this, snapshot);
        super.update(snapshot);
    }

    private boolean keepRunning() {
        return this.running.getOpaque() && !Thread.currentThread().isInterrupted();
    }

    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
        return new ControlPlaneFragment(this.config.clone(cloneConfig));
    }

    @Override
    public boolean isDrained() {
        return super.isDrained() && this.metrics.getInProgress() == 0;
    }

    @Override
    public void setDrainMode(boolean value) {
        DRAIN.setRelease(this, value);
        super.setDrainMode(value);
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            if (this.mainThread != null) {
                try {
                    this.mainThread.interrupt();
                    LockSupport.unpark(this.mainThread);
                    this.mainThread.interrupt();
                    this.mainThread.join(500);
                } catch (Exception ignored) {
                }
                this.mainThread = null;
            }
            dumpLocks();
            this.metrics.close();
        }
        super.close();
        this.logger.trace("Closed");
    }

    @Override
    public void dumpLocks() {
        if (this.mainExecutor != null) {
            this.mainExecutor.close();
        }
    }

    private class CycleState {

        final FlowRecorder batchRecorder = new FlowRecorder();
        final FlowRecorder throughputRecorder = new FlowRecorder();
        final FlowRecorder batchEfficiency = new FlowRecorder(Duration.ofSeconds(1), 0.10);
        final FlowRecorder requestEfficiency = new FlowRecorder(Duration.ofSeconds(1), 0.10);

        long batchStart = 0;
        long batchSize = 2;
        long completed = 0;

        long upstreamCount = 0;

        void reset() {
            GlobalState.resetThroughput(ControlPlaneFragment.this.socket,
                    ControlPlaneFragment.this.cpu);
            this.batchRecorder.reset();
            this.batchEfficiency.reset();
            this.requestEfficiency.reset();
            this.throughputRecorder.reset();

            this.batchStart = 0;
            this.batchSize = 2;
            this.completed = 0;
        }
    }
}
