package euhedral.io;

import euhedral.hardware_utils.ThreadTools;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.hardware_utils.pinning.PinnedThreadExecutor;
import euhedral.io.utils.DemandOptimizer;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;

import lombok.Setter;

public class SlotManagerSMTBuddy implements AutoCloseable {
    protected final SMTState state;

    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final DrainBuffer bufferWrapper;
    protected final int bufferSize;
    protected final int lowWaterMark;

    @Setter
    protected volatile IngestSequencer ingest;
    protected volatile PinnedThreadExecutor executor;
    protected volatile Thread cycleThread;

    public SlotManagerSMTBuddy(DrainBuffer buffer, SMTState state) {
        this.state = state;
        this.bufferWrapper = buffer;
        this.bufferSize = buffer.getSize();
        this.lowWaterMark = bufferSize >> 2;
    }

    public boolean start(int cpu, String name, int priority, boolean daemon) {
        if(running.compareAndSet(false, true)) {
            this.executor = PinnedThreadExecutor.getOrSetIfAbsent(cpu, name, priority, daemon);
            executor.execute(this::cycle);
        } else {
            return false;
        }
        return true;
    }

    private void cycle() {
        ThreadTools.setTimerResolution(1);
        while(!Thread.interrupted() && running.get()) {
            if(ingest == null) {
                LockSupport.parkNanos(20_000);
                continue;
            }

            if(doStuffInternal()) {
                Thread.onSpinWait();
                continue;
            }

            if(ingest.getCount() == 0) {
                Thread.yield();
                continue;
            }

            int bufferCount = state.bufferCount.get();
            if(bufferCount > lowWaterMark) {
                Thread.onSpinWait();
                continue;
            }

            long count = ingest.drain(bufferWrapper, bufferSize - state.bufferCount.get(), 0);
            if(count > 0) {
                state.bufferCount.addAndGet((int) count);
            }

            LockSupport.parkNanos((long) ((state.demandWaitNs - state.nowNs) * 0.8));
        }
    }

    public boolean doStuff() {
        if(ingest != null && !running.get()) {
            if(!doStuffInternal()) {
                int bufferCount = state.bufferCount.get();
                if(bufferCount > lowWaterMark) {
                    return false;
                }
                long count = ingest.drain(bufferWrapper, bufferSize - state.bufferCount.get(), 0);
                if(count > 0) {
                    state.bufferCount.addAndGet((int) count);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean doStuffInternal() {
        if (state.fillRecorder == null) {
            state.fillRecorder = ingest.getFillRecorder();
            state.fillBytesRecorder = ingest.getFillBytesRecorder();
            state.drainRecorder = ingest.getDrainRecorder();

            state.fill = state.fillRecorder.getFlowSnapshot();
            state.fillBytes = state.fillBytesRecorder.getFlowSnapshot();
            state.drain = state.drainRecorder.getFlowSnapshot();
        }

        state.nowNs = System.nanoTime();
        if(state.nowNs < state.demandWaitNs) {
            Thread.onSpinWait();
            return false;
        }
        state.refresh();
        long demand = calculateDemand(ingest.getCount());
        int maxFill = bufferSize - state.bufferCount.get();
        state.bufferCount.addAndGet((int) ingest.drain(bufferWrapper, maxFill, demand));

        state.refresh();
        state.demandWaitNs = calculateDemandWaitNs(System.nanoTime(), maxFill);
        return true;
    }

    protected long calculateDemand(long ingestCount) {
        double drainRate = state.drain.avgRate;
        double drainRateVar = state.drain.rateVariation;
        double arrivalLatencyNs = state.arrival.avgUnits;
        double arrivalLatencyVar = state.arrival.unitVariation;
        double avgFrameSize =
                state.fillBytes.avgUnits + state.fillBytes.unitVariation;

        int bufferCount = state.bufferCount.get();
        long demand = DemandOptimizer.getDemand(drainRate, arrivalLatencyNs, drainRateVar,
                arrivalLatencyVar, bufferCount + ingestCount, (long) avgFrameSize,
                ingest.getMaxQueuedBytes());

        long maxFill = bufferSize - bufferCount;
        if (demand < maxFill) {
            demand += maxFill - bufferCount;
        }

        return demand;
    }

    protected long calculateDemandWaitNs(long nowNs, long maxFill) {
        boolean warmedUp = ingest.getFillRecorder().getRollingSum() > bufferSize
                && state.fill.avgInterval > 0 && state.fill.avgUnits > 0;

        if (warmedUp) {
            double fillRate = state.fill.avgRate;
            double execLatency = state.exec.avgUnits;

            double execRate = 1.0 / Math.max(execLatency, 1.0);

            if (fillRate < execRate * 0.5) {
                return nowNs + (long) state.fill.avgInterval;
            } else {
                double fillInterval =
                        state.fill.avgInterval + state.fill.intervalVariation;
                fillInterval = Math.max(fillInterval, 1_000);

                double avgFill = state.fill.avgUnits + state.fill.unitVariation;
                avgFill = Math.max(avgFill, 64);

                double intervalCount = maxFill / avgFill;

                long maxWaitNs = (long) (execLatency * bufferSize / 2);
                maxWaitNs = Math.min(maxWaitNs, state.maxParkNs * 4);

                long fillWait = (long) (intervalCount * fillInterval);
                return nowNs + Math.min(maxWaitNs, fillWait);
            }
        }
        return 0;
    }

    @Override
    public void close() {
        if(running.compareAndSet(true, false)) {
            cycleThread.interrupt();
            LockSupport.unpark(cycleThread);

            try {
                cycleThread.join(500);
            } catch (Exception ignored) {

            }
            executor.close();
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

        public SMTState(FlowRecorder executionLatency, FlowRecorder arrivalLatency, long maxParkNs) {
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
