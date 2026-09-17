package calibration.infra;

import calibration.config.CalibrationBenchmarkConfig;
import io.euhedral_execution.core.control_plane.FragmentObserver;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicReferenceArray;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public final class BenchmarkObserver extends FragmentObserver {

    private final CalibrationBenchmarkConfig config;
    private final AtomicReference<PaddedAtomicReferenceArray<HighSpeedMetrics>> metrics = new AtomicReference<>();

    public BenchmarkObserver(CalibrationBenchmarkConfig config) {
        this.config = config;
    }

    public void startObserving() {
        PaddedAtomicReferenceArray<HighSpeedMetrics> metrics =
                new PaddedAtomicReferenceArray<>(SystemInfo.getMaxCoreId() + 1, true, true);
        BitSet cores = SystemInfo.getCoreSet();
        for (int core = cores.nextSetBit(0); core >= 0; core = cores.nextSetBit(core + 1)) {
            metrics.setPlain(core, new HighSpeedMetrics(core, this.config.rawSampleLimit()));
        }
        this.metrics.set(metrics);
    }

    public PaddedAtomicReferenceArray<HighSpeedMetrics> stopObserving() {
        return this.metrics.getAndSet(null);
    }

    @Override
    protected void batchProgressState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double avgServiceTime) {
        if (!this.config.observeBatchProgress() || !Double.isFinite(avgServiceTime)) {
            return;
        }
        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics != null) {
            coreMetrics.recordBatchProgress(
                    cycleEpoch,
                    batchEpoch,
                    upstreamCount,
                    registeredWorkers,
                    productiveHandleCount,
                    workerRank,
                    contention,
                    avgServiceTime);
        }
    }

    @Override
    protected void batchCompleteState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput) {
        if (!this.config.observeBatchComplete() || !Double.isFinite(avgServiceTime) || !Double.isFinite(throughput)) {
            return;
        }
        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics != null) {
            coreMetrics.recordBatchComplete(
                    cycleEpoch,
                    batchEpoch,
                    upstreamCount,
                    registeredWorkers,
                    productiveHandleCount,
                    workerRank,
                    contention,
                    avgServiceTime,
                    throughput);
        }
    }

    private @Nullable HighSpeedMetrics getCoreMetrics(int core) {
        PaddedAtomicReferenceArray<HighSpeedMetrics> metricArray = this.metrics.getOpaque();
        return metricArray == null ? null : metricArray.getOpaque(core);
    }

    public static final class HighSpeedMetrics {

        public final int core;
        public final int rawSampleLimit;

        public final long[][] batchProgressHeadState;
        public final double[] batchProgressHeadAvgServiceTime;
        public final long[][] batchProgressSteadyStateState;
        public final double[] batchProgressSteadyStateAvgServiceTime;

        public final long[][] batchCompleteHeadState;
        public final double[] batchCompleteHeadAvgServiceTime;
        public final double[] batchCompleteHeadThroughput;
        public final long[][] batchCompleteSteadyStateState;
        public final double[] batchCompleteSteadyStateAvgServiceTime;
        public final double[] batchCompleteSteadyStateThroughput;

        public long batchProgressObservations;
        public long batchCompleteObservations;

        private final int mask;
        private boolean aligned;

        public HighSpeedMetrics(int rawSampleLimit) {
            this(-1, rawSampleLimit);
        }

        public HighSpeedMetrics(int core, int rawSampleLimit) {
            this.core = core;
            this.rawSampleLimit = Integer.highestOneBit((rawSampleLimit - 1) << 1);
            this.mask = this.rawSampleLimit - 1;

            this.batchProgressHeadState = new long[this.rawSampleLimit][7];
            this.batchProgressHeadAvgServiceTime = new double[this.rawSampleLimit];
            this.batchProgressSteadyStateState = new long[this.rawSampleLimit][7];
            this.batchProgressSteadyStateAvgServiceTime = new double[this.rawSampleLimit];

            this.batchCompleteHeadState = new long[this.rawSampleLimit][7];
            this.batchCompleteHeadAvgServiceTime = new double[this.rawSampleLimit];
            this.batchCompleteHeadThroughput = new double[this.rawSampleLimit];
            this.batchCompleteSteadyStateState = new long[this.rawSampleLimit][7];
            this.batchCompleteSteadyStateAvgServiceTime = new double[this.rawSampleLimit];
            this.batchCompleteSteadyStateThroughput = new double[this.rawSampleLimit];
        }

        public void recordBatchProgress(
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                long productiveHandleCount,
                int workerRank,
                long contention,
                double avgServiceTime) {
            int index = (int) (this.batchProgressObservations & this.mask);
            if (this.batchProgressObservations++ < this.rawSampleLimit) {
                recordState(
                        this.batchProgressHeadState[index],
                        cycleEpoch,
                        batchEpoch,
                        upstreamCount,
                        registeredWorkers,
                        productiveHandleCount,
                        workerRank,
                        contention);
                this.batchProgressHeadAvgServiceTime[index] = avgServiceTime;
            }
            recordState(
                    this.batchProgressSteadyStateState[index],
                    cycleEpoch,
                    batchEpoch,
                    upstreamCount,
                    registeredWorkers,
                    productiveHandleCount,
                    workerRank,
                    contention);
            this.batchProgressSteadyStateAvgServiceTime[index] = avgServiceTime;
        }

        public void recordBatchComplete(
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                long productiveHandleCount,
                int workerRank,
                long contention,
                double avgServiceTime,
                double throughput) {
            int index = (int) (this.batchCompleteObservations & this.mask);
            if (this.batchCompleteObservations++ < this.rawSampleLimit) {
                recordState(
                        this.batchCompleteHeadState[index],
                        cycleEpoch,
                        batchEpoch,
                        upstreamCount,
                        registeredWorkers,
                        productiveHandleCount,
                        workerRank,
                        contention);
                this.batchCompleteHeadAvgServiceTime[index] = avgServiceTime;
                this.batchCompleteHeadThroughput[index] = throughput;
            }
            recordState(
                    this.batchCompleteSteadyStateState[index],
                    cycleEpoch,
                    batchEpoch,
                    upstreamCount,
                    registeredWorkers,
                    productiveHandleCount,
                    workerRank,
                    contention);
            this.batchCompleteSteadyStateAvgServiceTime[index] = avgServiceTime;
            this.batchCompleteSteadyStateThroughput[index] = throughput;
        }

        private static void recordState(
                long[] state,
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                long productiveHandleCount,
                int workerRank,
                long contention) {
            state[0] = cycleEpoch;
            state[1] = batchEpoch;
            state[2] = upstreamCount;
            state[3] = registeredWorkers;
            state[4] = workerRank;
            state[5] = contention;
            state[6] = productiveHandleCount;
        }

        public void align() {
            if (this.aligned) {
                return;
            }
            long[][] longAligner = new long[this.rawSampleLimit][];
            align(longAligner, this.batchProgressSteadyStateState, this.batchProgressObservations);
            align(longAligner, this.batchCompleteSteadyStateState, this.batchCompleteObservations);

            double[] doubleAligner = new double[this.rawSampleLimit];
            align(doubleAligner, this.batchProgressSteadyStateAvgServiceTime, this.batchProgressObservations);
            align(doubleAligner, this.batchCompleteSteadyStateAvgServiceTime, this.batchCompleteObservations);
            align(doubleAligner, this.batchCompleteSteadyStateThroughput, this.batchCompleteObservations);
            this.aligned = true;
        }

        private void align(long[][] aligner, long[][] target, long count) {
            if (count == target.length || count == 0) {
                return;
            }
            int index = 0;
            for (int i = 0; i < target.length && count > 0; i++) {
                aligner[index++] = target[(int) (--count & this.mask)];
            }
            for (int i = 0, length = index; i < length; i++) {
                target[i] = aligner[--index];
            }
        }

        private void align(double[] aligner, double[] target, long count) {
            if (count == target.length || count == 0) {
                return;
            }
            int index = 0;
            for (int i = 0; i < target.length && count > 0; i++) {
                aligner[index++] = target[(int) (--count & this.mask)];
            }
            for (int i = 0, length = index; i < length; i++) {
                target[i] = aligner[--index];
            }
        }
    }
}
