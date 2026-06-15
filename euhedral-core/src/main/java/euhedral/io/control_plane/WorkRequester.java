package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.DemandOptimizer;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.MathFunctions;
import io.euhedral_execution.data_structures.queues.common.BatchableQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    protected final RequesterState requesterState;
    protected final BatchableQueue<AbstractFrame> L1Cache;
    protected final DrainBuffer bufferWrapper;
    protected final FlowRecorder executionLatency = new FlowRecorder();
    protected final int L1Size;
    protected final int lowWaterMark;

    private final PinnedThreadExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    protected volatile Thread cycleThread;

    public WorkRequester(@NonNull CacheConfig cacheConfig, long maxParkNs,
            DrainBuffer buffer, PinnedThreadExecutor executor) {
        super(cacheConfig);

        if (buffer == null) {
            this.requesterState = null;
            this.L1Cache = null;
            this.bufferWrapper = null;
            this.executor = null;
            this.L1Size = 0;
            this.lowWaterMark = 0;
        } else {
            this.requesterState = new RequesterState(this.executionLatency,
                    buffer.arrivalLatencyRecorder, super.fillRecorder.getPlain(),
                    super.fillBytesRecorder.getPlain(),
                    super.drainRecorder, maxParkNs);
            this.L1Cache = buffer.buffer;
            this.bufferWrapper = buffer;
            this.L1Size = buffer.getSize();
            this.lowWaterMark = L1Size >> 2;
            this.executor = executor;
        }

    }

    public void start() {
        if (this.running.compareAndSet(false, true)) {
            this.executor.execute(this::cycle);
        }
    }

    private void cycle() {
        super.register();

        ThreadTools.setTimerResolution(1);
        while (!Thread.interrupted() && this.running.getOpaque()) {
            int bufferCount = this.requesterState.bufferCount.getAcquire();
            if (bufferCount > this.lowWaterMark) {
                Thread.onSpinWait();
                continue;
            }

            if (!pull()) {
                Thread.onSpinWait();
                continue;
            }

            LockSupport.parkNanos(
                    (long) ((this.requesterState.demandWaitNs - this.requesterState.nowNs) * 0.8));
        }
    }

    protected void manualPull() {
        if (!this.running.getOpaque()) {
            int bufferCount = this.requesterState.bufferCount.getPlain();
            if (bufferCount > this.lowWaterMark) {
                return;
            }
            pull();
        }
    }

    /// Pulls work from the ingest queue into the local execution buffer.
    ///
    /// Demand is calculated dynamically from observed flow rates, latency, queue pressure, and
    /// current buffer occupancy. Pull frequency is also rate-limited to avoid over-requesting work
    /// under load.
    ///
    /// The local buffer acts as a small execution window that smooths ingest jitter while keeping
    /// queue residency low.
    ///
    /// Returns `true` when a pull cycle was executed.
    private boolean pull() {
        this.requesterState.nowNs = System.nanoTime();
        this.requesterState.refresh();
        if (this.requesterState.nowNs < this.requesterState.demandWaitNs) {
            pullNoDemand();
            return false;
        }

        long demand = calculateDemand(super.getL2CacheCount());
        int l1MaxFill = this.L1Size - this.requesterState.bufferCount.get();
        long l2Fill = calculateL2Pull(l1MaxFill);
        int count = (int) super.pull(this.bufferWrapper, l1MaxFill, l2Fill, demand);

        if (count > 0) {
            this.requesterState.bufferCount.addAndGet(count);
        }

        this.requesterState.refresh();
        this.requesterState.demandWaitNs = calculateDemandWaitNs(System.nanoTime(), l1MaxFill);
        return true;
    }

    private void pullNoDemand() {
        this.requesterState.refresh();
        int l1MaxFill = this.L1Size - this.requesterState.bufferCount.get();
        long l2Fill = calculateL2Pull(l1MaxFill);
        int count = (int) super.pull(this.bufferWrapper, l1MaxFill, l2Fill, 0);

        if (count > 0) {
            this.requesterState.bufferCount.addAndGet(count);
        }
    }

    private long calculateL2Pull(int l1MaxFill) {
        long execThroughput = this.requesterState.exec.throughputNs;
        if(execThroughput == 0) {
            return Math.max(l1MaxFill - super.getL2CacheCount(), 0L);
        }

        long l2Pull = execThroughput - super.getL2CacheCount();
        l2Pull = (3 * l2Pull) >>> 1; // 1.5x

        return MathFunctions.clampLong(l2Pull, 0L, super.getL2MaxQueueCount());
    }

    private long calculateDemand(long ingestCount) {
        double drainRate = this.requesterState.drain.avgRate;
        double drainRateVar = this.requesterState.drain.rateVariation;
        double arrivalLatencyNs = this.requesterState.arrival.avgUnits;
        double arrivalLatencyVar = this.requesterState.arrival.unitVariation;
        double avgFrameSize =
                this.requesterState.fillBytes.avgUnits
                        + this.requesterState.fillBytes.unitVariation;

        int bufferCount = this.requesterState.bufferCount.getAcquire();
        long demand = DemandOptimizer.getDemand(drainRate, arrivalLatencyNs, drainRateVar,
                arrivalLatencyVar, bufferCount + ingestCount, (long) avgFrameSize,
                super.getL2MaxQueuedBytes());

        long maxFill = this.L1Size - bufferCount;
        return Math.max(demand, maxFill);
    }

    /// Calculates when the next ingest pull should occur.
    ///
    /// Pull timing adapts to observed ingest cadence, execution latency, and buffer utilization.
    ///
    /// Under light traffic, the executor waits longer to avoid pointless polling. Under sustained
    /// load, pulls happen more aggressively to keep execution pipelines full without flooding local
    /// buffers.
    ///
    /// The result is a soft pacing mechanism that reduces queue churn while maintaining steady
    /// flow.
    private long calculateDemandWaitNs(long nowNs, long maxFill) {
        boolean warmedUp = this.requesterState.fillRecorder.getRollingSum() > this.L1Size
                && this.requesterState.fill.avgInterval > 0
                && this.requesterState.fill.avgUnits > 0;

        if (warmedUp) {
            FlowRecorder.FlowSnapshot fill = this.requesterState.fill;
//            double fillRate = this.requesterState.fill.avgRate;
            double execLatency = this.requesterState.exec.avgUnits;

            double fillInterval =
                    fill.avgInterval + fill.intervalVariation;
            fillInterval = Math.max(fillInterval, 1_000);

            double avgFill = fill.avgUnits + fill.unitVariation;
            avgFill = Math.max(avgFill, 64);

            double intervalCount = maxFill / avgFill;

            long maxWaitNs = (long) (execLatency * this.L1Size / 2);
            maxWaitNs = Math.min(maxWaitNs, this.requesterState.maxParkNs << 2);

            long fillWait = (long) (intervalCount * fillInterval);
            return nowNs + Math.min(maxWaitNs, fillWait);
        }
        return 0;
    }

    public int getL1CacheCount() {
        return this.requesterState.bufferCount.getAcquire();
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            this.cycleThread.interrupt();
            LockSupport.unpark(this.cycleThread);

            try {
                this.cycleThread.join(500);
            } catch (Exception ignored) {

            }
            this.executor.close();
            super.close();
        }
    }

    public static class RequesterState {

        public final long maxParkNs;

        public final FlowRecorder executionLatency;
        public final FlowRecorder arrivalLatency;

        public final FlowRecorder fillRecorder;
        public final FlowRecorder fillBytesRecorder;
        public final FlowRecorder drainRecorder;

        public final FlowSnapshot exec;
        public final FlowSnapshot arrival;

        public final FlowSnapshot fill;
        public final FlowSnapshot fillBytes;
        public final FlowSnapshot drain;

        public long demandWaitNs = 0;

        public AtomicInteger bufferCount = new AtomicInteger(0);

        private long nowNs = 0;

        public RequesterState(FlowRecorder executionLatency, FlowRecorder arrivalLatency,
                FlowRecorder fillRecorder, FlowRecorder fillBytesRecorder,
                FlowRecorder drainRecorder,
                long maxParkNs) {
            this.maxParkNs = maxParkNs;
            this.arrivalLatency = arrivalLatency;
            this.executionLatency = executionLatency;
            this.fillRecorder = fillRecorder;
            this.fillBytesRecorder = fillBytesRecorder;
            this.drainRecorder = drainRecorder;

            this.exec = executionLatency.getFlowSnapshot();
            this.arrival = arrivalLatency.getFlowSnapshot();
            this.fill = fillRecorder.getFlowSnapshot();
            this.fillBytes = fillBytesRecorder.getFlowSnapshot();
            this.drain = drainRecorder.getFlowSnapshot();
        }

        public void refresh() {
            executionLatency.refreshSnapshot(exec, true);
            arrivalLatency.refreshSnapshot(arrival, false);
            fillRecorder.refreshSnapshot(fill, true);
            fillBytesRecorder.refreshSnapshot(fillBytes, true);
            drainRecorder.refreshSnapshot(drain, false);
        }
    }
}
