package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.control_plane.ControlPlaneCache.DownstreamHandle;
import euhedral.io.utils.DemandOptimizer;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class SMTBuddy implements AutoCloseable {

    protected static final VarHandle INGEST;

    static {
        try {
            INGEST = MethodHandles.lookup()
                    .findVarHandle(SMTBuddy.class, "ingest", ControlPlaneCache.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final SMTState state;

    protected final PinnedThreadExecutor executor;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final DownstreamHandle handle;
    protected final DrainBuffer bufferWrapper;
    protected final int bufferSize;
    protected final int lowWaterMark;

    protected ControlPlaneCache ingest;
    protected volatile Thread cycleThread;

    public SMTBuddy(DownstreamHandle handle, DrainBuffer buffer, SMTState state, PinnedThreadExecutor executor) {
        this.state = state;
        this.handle = handle;
        this.bufferWrapper = buffer;
        this.bufferSize = buffer.getSize();
        this.lowWaterMark = bufferSize >> 2;
        this.executor = executor;
    }

    public void setIngest(ControlPlaneCache ingest) {
        INGEST.setRelease(this, ingest);
    }

    public boolean start() {
        if (this.running.compareAndSet(false, true)) {
            this.executor.execute(this::cycle);
        } else {
            return false;
        }
        return true;
    }

    private void cycle() {
        ThreadTools.setTimerResolution(1);
        while (!Thread.interrupted() && this.running.getOpaque()) {
            ControlPlaneCache ingest = (ControlPlaneCache) INGEST.getOpaque(this);
            if (ingest == null) {
                LockSupport.parkNanos(20_000);
                continue;
            }

            int bufferCount = this.state.bufferCount.getAcquire();
            if (bufferCount > this.lowWaterMark) {
                Thread.onSpinWait();
                continue;
            }

            if (!pull()) {
                Thread.onSpinWait();
                continue;
            }

            LockSupport.parkNanos((long) ((this.state.demandWaitNs - this.state.nowNs) * 0.8));
        }
    }

    public void doStuff() {
        ControlPlaneCache ingest = (ControlPlaneCache)  INGEST.getOpaque(this);
        if (ingest != null && !this.running.getOpaque()) {
            int bufferCount = this.state.bufferCount.getPlain();
            if (bufferCount > this.lowWaterMark) {
                return;
            }
            pull();
        }
    }

    /// Pulls work from the ingest queue into the local execution buffer.
    ///
    /// Demand is calculated dynamically from observed flow rates, latency, queue pressure, and current
    /// buffer occupancy. Pull frequency is also rate-limited to avoid over-requesting work under load.
    ///
    /// The local buffer acts as a small execution window that smooths ingest jitter while keeping queue
    /// residency low.
    ///
    /// Returns `true` when a pull cycle was executed.
    private boolean pull() {
        if (this.state.fillRecorder == null) {
            this.state.fillRecorder = ingest.getFillRecorder();
            this.state.fillBytesRecorder = ingest.getFillBytesRecorder();
            this.state.drainRecorder = handle.drainRecorder;

            this.state.fill = this.state.fillRecorder.getFlowSnapshot();
            this.state.fillBytes = this.state.fillBytesRecorder.getFlowSnapshot();
            this.state.drain = this.state.drainRecorder.getFlowSnapshot();
        }

        this.state.nowNs = System.nanoTime();
        if (this.state.nowNs < this.state.demandWaitNs) {
            Thread.onSpinWait();
            return false;
        }
        ControlPlaneCache ingest = (ControlPlaneCache)  INGEST.getOpaque(this);

        this.state.refresh();
        long demand = calculateDemand(ingest.getTotalCount());
        int maxFill = this.bufferSize - this.state.bufferCount.get();
        int count = (int) ingest.drain(handle, this.bufferWrapper, maxFill, demand);

        if(count > 0) {
            this.state.bufferCount.addAndGet(count);
        }

        this.state.refresh();
        this.state.demandWaitNs = calculateDemandWaitNs(System.nanoTime(), maxFill);
        return true;
    }

    protected long calculateDemand(long ingestCount) {
        double drainRate = this.state.drain.avgRate;
        double drainRateVar = this.state.drain.rateVariation;
        double arrivalLatencyNs = this.state.arrival.avgUnits;
        double arrivalLatencyVar = this.state.arrival.unitVariation;
        double avgFrameSize =
                this.state.fillBytes.avgUnits + this.state.fillBytes.unitVariation;

        ControlPlaneCache ingest = (ControlPlaneCache)  INGEST.getOpaque(this);
        int bufferCount = this.state.bufferCount.getAcquire();
        long demand = DemandOptimizer.getDemand(drainRate, arrivalLatencyNs, drainRateVar,
                arrivalLatencyVar, bufferCount + ingestCount, (long) avgFrameSize,
                ingest.getProportionalMaxQueuedBytes());

        long maxFill = this.bufferSize - bufferCount;
        if (demand < maxFill) {
            demand += maxFill - bufferCount;
        }

        return demand;
    }

    /// Calculates when the next ingest pull should occur.
    ///
    /// Pull timing adapts to observed ingest cadence, execution latency, and buffer utilization.
    ///
    /// Under light traffic, the executor waits longer to avoid pointless polling. Under sustained
    /// load, pulls happen more aggressively to keep execution pipelines full without flooding local
    /// buffers.
    ///
    /// The result is a soft pacing mechanism that reduces queue churn while maintaining steady flow.
    protected long calculateDemandWaitNs(long nowNs, long maxFill) {
        ControlPlaneCache ingest = (ControlPlaneCache)  INGEST.getOpaque(this);
        boolean warmedUp = ingest.getFillRecorder().getRollingSum() > this.bufferSize
                && this.state.fill.avgInterval > 0 && this.state.fill.avgUnits > 0;

        if (warmedUp) {
            FlowRecorder.FlowSnapshot fill = this.state.fill;
            double fillRate = this.state.fill.avgRate;
            double execLatency = this.state.exec.avgUnits;

            double execRate = 1.0 / Math.max(execLatency, 1.0);

            if (fillRate < execRate * 0.5) {
                return nowNs + (long) fill.avgInterval;
            } else {
                double fillInterval =
                        fill.avgInterval + fill.intervalVariation;
                fillInterval = Math.max(fillInterval, 1_000);

                double avgFill = fill.avgUnits + fill.unitVariation;
                avgFill = Math.max(avgFill, 64);

                double intervalCount = maxFill / avgFill;

                long maxWaitNs = (long) (execLatency * this.bufferSize / 2);
                maxWaitNs = Math.min(maxWaitNs, this.state.maxParkNs * 4);

                long fillWait = (long) (intervalCount * fillInterval);
                return nowNs + Math.min(maxWaitNs, fillWait);
            }
        }
        return 0;
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
        }
    }

    public static class SMTState {

        public final long maxParkNs;

        public final FlowRecorder executionLatency;
        public final FlowRecorder arrivalLatency;
        public final FlowSnapshot exec;
        public final FlowSnapshot arrival;

        public FlowRecorder fillRecorder = null;
        public FlowRecorder fillBytesRecorder = null;
        public FlowRecorder drainRecorder = null;

        public FlowSnapshot fill;
        public FlowSnapshot fillBytes;
        public FlowSnapshot drain;

        public long demandWaitNs = 0;

        public AtomicInteger bufferCount = new AtomicInteger(0);

        private long nowNs = 0;

        public SMTState(FlowRecorder executionLatency, FlowRecorder arrivalLatency,
                long maxParkNs) {
            this.maxParkNs = maxParkNs;
            this.arrivalLatency = arrivalLatency;
            this.executionLatency = executionLatency;
            this.arrival = arrivalLatency.getFlowSnapshot();
            this.exec = executionLatency.getFlowSnapshot();
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
