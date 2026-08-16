package calibration.infra;

import calibration.config.CalibrationBenchmarkConfig;
import io.euhedral_execution.core.control_plane.FragmentControlConfig;
import io.euhedral_execution.core.control_plane.FragmentObserver;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicReferenceArray;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public class BenchmarkObserver extends FragmentObserver {

    private final CalibrationBenchmarkConfig config;
    private final AtomicReference<PaddedAtomicReferenceArray<HighSpeedMetrics>> metrics = new AtomicReference<>();

    public BenchmarkObserver(CalibrationBenchmarkConfig config) {
        this.config = config;
    }

    public void startObserving() {
        PaddedAtomicReferenceArray<HighSpeedMetrics> metrics =
                new PaddedAtomicReferenceArray<>(SystemInfo.getMaxCoreId(), true, true);
        BitSet cores = SystemInfo.getCpuSet();
        for (int i = cores.nextSetBit(0); i >= 0; i = cores.nextSetBit(i + 1)) {
            metrics.setPlain(i, new HighSpeedMetrics(this.config.rawSampleLimit()));
        }
        this.metrics.set(metrics);
    }

    public PaddedAtomicReferenceArray<HighSpeedMetrics> stopObserving() {
        return this.metrics.getAndSet(null);
    }

    @Override
    protected void cycleStartState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long completed,
            long batchSize,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double throughput) {
        if (!this.config.observeCycleStart() || !Double.isFinite(throughput)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordCycleStart(
                cycleEpoch,
                batchEpoch,
                completed,
                batchSize,
                upstreamCount,
                registeredWorkers,
                workerRank,
                contention,
                throughput);
    }

    @Override
    protected void batchProgressState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double avgServiceTime) {
        if (!this.config.observeBatchProgress() || !Double.isFinite(avgServiceTime)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordBatchProgress(
                cycleEpoch, batchEpoch, upstreamCount, registeredWorkers, workerRank, contention, avgServiceTime);
    }

    @Override
    protected void batchCompleteState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput) {
        if (!this.config.observeBatchComplete() || !Double.isFinite(avgServiceTime) || !Double.isFinite(throughput)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordBatchComplete(
                cycleEpoch,
                batchEpoch,
                upstreamCount,
                registeredWorkers,
                workerRank,
                contention,
                avgServiceTime,
                throughput);
    }

    @Override
    protected void rawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost) {
        if (!this.config.observeRawBodyCost()) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordRawBodyCost(cycleEpoch, batchEpoch, rawBodyCost);
    }

    @Override
    protected void idleBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            long contention,
            double smoothedBodyCost) {
        if (!this.config.observeIdleDecision() || !Double.isFinite(smoothedBodyCost)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordIdle(cycleEpoch, batchEpoch, contentionPolicy, bodyPolicy, contention, smoothedBodyCost);
    }

    @Override
    protected void execBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            long contention,
            double smoothedBodyCost) {
        if (!this.config.observeExecDecision() || !Double.isFinite(smoothedBodyCost)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordExec(cycleEpoch, batchEpoch, contentionPolicy, bodyPolicy, contention, smoothedBodyCost);
    }

    private @Nullable HighSpeedMetrics getCoreMetrics(int core) {
        PaddedAtomicReferenceArray<HighSpeedMetrics> metricArray = this.metrics.getOpaque();
        if (metricArray == null) {
            return null;
        }
        return metricArray.getOpaque(core);
    }

    public static final class HighSpeedMetrics {

        public final long[][] cycleStartWarmupState;
        public final double[] cycleStartWarmupThroughput;
        public final long[][] cycleStartTailState;
        public final double[] cycleStartTailThroughput;

        public final long[][] batchProgressWarmupState;
        public final double[] batchProgressWarmupAvgServiceTime;
        public final long[][] batchProgressTailState;
        public final double[] batchProgressTailAvgServiceTime;

        public final long[][] batchCompleteWarmupState;
        public final double[] batchCompleteWarmupAvgServiceTime;
        public final double[] batchCompleteWarmupThroughput;
        public final long[][] batchCompleteTailState;
        public final double[] batchCompleteTailAvgServiceTime;
        public final double[] batchCompleteTailThroughput;

        public final long[][] rawBodyCostTailState;

        public final long[][] idleBranchDecisionTotal =
                new long[FragmentControlConfig.POLICY_COUNT][FragmentControlConfig.IDLE_WEIGHT_SETS];
        public final long[][] idleWarmupDecisionState;
        public final double[] idleWarmupSmoothedBodyCost;
        public final long[][] idleTailDecisionState;
        public final double[] idleTailSmoothedBodyCost;

        public final long[][] execBranchDecisionTotal =
                new long[FragmentControlConfig.POLICY_COUNT][FragmentControlConfig.EXEC_WEIGHT_SETS];
        public final long[][] execWarmupDecisionState;
        public final double[] execWarmupSmoothedBodyCost;
        public final long[][] execTailDecisionState;
        public final double[] execTailSmoothedBodyCost;

        public long cycleStartObservations = 0;
        public long batchProgressObservations = 0;
        public long batchCompleteObservations = 0;
        public long rawBodyCostTotal = 0;
        public long rawBodyCostObservations = 0;
        public long idleDecisionObservations = 0;
        public long execDecisionObservations = 0;

        public final int rawSampleLimit;
        private final int mask;

        private boolean aligned = false;

        public HighSpeedMetrics(int rawSampleLimit) {
            rawSampleLimit = Integer.highestOneBit((rawSampleLimit - 1) << 1);
            this.rawSampleLimit = rawSampleLimit;
            this.mask = rawSampleLimit - 1;

            this.cycleStartWarmupState = new long[rawSampleLimit][8];
            this.cycleStartWarmupThroughput = new double[rawSampleLimit];
            this.cycleStartTailState = new long[rawSampleLimit][8];
            this.cycleStartTailThroughput = new double[rawSampleLimit];

            this.batchProgressWarmupState = new long[rawSampleLimit][6];
            this.batchProgressWarmupAvgServiceTime = new double[rawSampleLimit];
            this.batchProgressTailState = new long[rawSampleLimit][6];
            this.batchProgressTailAvgServiceTime = new double[rawSampleLimit];

            this.batchCompleteWarmupState = new long[rawSampleLimit][6];
            this.batchCompleteWarmupAvgServiceTime = new double[rawSampleLimit];
            this.batchCompleteWarmupThroughput = new double[rawSampleLimit];
            this.batchCompleteTailState = new long[rawSampleLimit][6];
            this.batchCompleteTailAvgServiceTime = new double[rawSampleLimit];
            this.batchCompleteTailThroughput = new double[rawSampleLimit];

            this.rawBodyCostTailState = new long[rawSampleLimit][3];

            this.idleWarmupDecisionState = new long[rawSampleLimit][5];
            this.idleWarmupSmoothedBodyCost = new double[rawSampleLimit];
            this.idleTailDecisionState = new long[rawSampleLimit][5];
            this.idleTailSmoothedBodyCost = new double[rawSampleLimit];

            this.execWarmupDecisionState = new long[rawSampleLimit][5];
            this.execWarmupSmoothedBodyCost = new double[rawSampleLimit];
            this.execTailDecisionState = new long[rawSampleLimit][5];
            this.execTailSmoothedBodyCost = new double[rawSampleLimit];
        }

        void recordCycleStart(
                long cycleEpoch,
                long batchEpoch,
                long completed,
                long batchSize,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double throughput) {
            int idx = (int) (cycleStartObservations & this.mask);
            if (cycleStartObservations++ < rawSampleLimit) {
                cycleStartWarmupState[idx][0] = cycleEpoch;
                cycleStartWarmupState[idx][1] = batchEpoch;
                cycleStartWarmupState[idx][2] = completed;
                cycleStartWarmupState[idx][3] = batchSize;
                cycleStartWarmupState[idx][4] = upstreamCount;
                cycleStartWarmupState[idx][5] = registeredWorkers;
                cycleStartWarmupState[idx][6] = workerRank;
                cycleStartWarmupState[idx][7] = contention;
                cycleStartWarmupThroughput[idx] = throughput;
            }
            cycleStartTailState[idx][0] = cycleEpoch;
            cycleStartTailState[idx][1] = batchEpoch;
            cycleStartTailState[idx][2] = completed;
            cycleStartTailState[idx][3] = batchSize;
            cycleStartTailState[idx][4] = upstreamCount;
            cycleStartTailState[idx][5] = registeredWorkers;
            cycleStartTailState[idx][6] = workerRank;
            cycleStartTailState[idx][7] = contention;
            cycleStartTailThroughput[idx] = throughput;
        }

        void recordBatchProgress(
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double avgServiceTime) {
            int idx = (int) (batchProgressObservations & this.mask);
            if (batchProgressObservations++ < rawSampleLimit) {
                batchProgressWarmupState[idx][0] = cycleEpoch;
                batchProgressWarmupState[idx][1] = batchEpoch;
                batchProgressWarmupState[idx][2] = upstreamCount;
                batchProgressWarmupState[idx][3] = registeredWorkers;
                batchProgressWarmupState[idx][4] = workerRank;
                batchProgressWarmupState[idx][5] = contention;
                batchProgressWarmupAvgServiceTime[idx] = avgServiceTime;
            }
            batchProgressTailState[idx][0] = cycleEpoch;
            batchProgressTailState[idx][1] = batchEpoch;
            batchProgressTailState[idx][2] = upstreamCount;
            batchProgressTailState[idx][3] = registeredWorkers;
            batchProgressTailState[idx][4] = workerRank;
            batchProgressTailState[idx][5] = contention;
            batchProgressTailAvgServiceTime[idx] = avgServiceTime;
        }

        void recordBatchComplete(
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double avgServiceTime,
                double throughput) {
            int idx = (int) (batchCompleteObservations & this.mask);
            if (batchCompleteObservations++ < rawSampleLimit) {
                batchCompleteWarmupState[idx][0] = cycleEpoch;
                batchCompleteWarmupState[idx][1] = batchEpoch;
                batchCompleteWarmupState[idx][2] = upstreamCount;
                batchCompleteWarmupState[idx][3] = registeredWorkers;
                batchCompleteWarmupState[idx][4] = workerRank;
                batchCompleteWarmupState[idx][5] = contention;
                batchCompleteWarmupAvgServiceTime[idx] = avgServiceTime;
                batchCompleteWarmupThroughput[idx] = throughput;
            }
            batchCompleteTailState[idx][0] = cycleEpoch;
            batchCompleteTailState[idx][1] = batchEpoch;
            batchCompleteTailState[idx][2] = upstreamCount;
            batchCompleteTailState[idx][3] = registeredWorkers;
            batchCompleteTailState[idx][4] = workerRank;
            batchCompleteTailState[idx][5] = contention;
            batchCompleteTailAvgServiceTime[idx] = avgServiceTime;
            batchCompleteTailThroughput[idx] = throughput;
        }

        void recordRawBodyCost(long cycleEpoch, long batchEpoch, long rawBodyCost) {
            int idx = (int) (rawBodyCostObservations & this.mask);
            rawBodyCostTailState[idx][0] = cycleEpoch;
            rawBodyCostTailState[idx][1] = batchEpoch;
            rawBodyCostTailState[idx][2] = rawBodyCost;
            rawBodyCostTotal += rawBodyCost;
            rawBodyCostObservations++;
        }

        void recordIdle(
                long cycleEpoch,
                long batchEpoch,
                int contentionPolicy,
                int bodyPolicy,
                long contention,
                double smoothedBodyCost) {
            int idx = (int) (idleDecisionObservations & this.mask);
            if (idleDecisionObservations++ < rawSampleLimit) {
                idleWarmupDecisionState[idx][0] = cycleEpoch;
                idleWarmupDecisionState[idx][1] = batchEpoch;
                idleWarmupDecisionState[idx][2] = contentionPolicy;
                idleWarmupDecisionState[idx][3] = bodyPolicy;
                idleWarmupDecisionState[idx][4] = contention;
                idleWarmupSmoothedBodyCost[idx] = smoothedBodyCost;
            }
            idleTailDecisionState[idx][0] = cycleEpoch;
            idleTailDecisionState[idx][1] = batchEpoch;
            idleTailDecisionState[idx][2] = contentionPolicy;
            idleTailDecisionState[idx][3] = bodyPolicy;
            idleTailDecisionState[idx][4] = contention;
            idleTailSmoothedBodyCost[idx] = smoothedBodyCost;

            idleBranchDecisionTotal[contentionPolicy][bodyPolicy]++;
        }

        void recordExec(
                long cycleEpoch,
                long batchEpoch,
                int contentionPolicy,
                int bodyPolicy,
                long contention,
                double smoothedBodyCost) {
            int idx = (int) (execDecisionObservations & this.mask);
            if (execDecisionObservations++ < rawSampleLimit) {
                execWarmupDecisionState[idx][0] = cycleEpoch;
                execWarmupDecisionState[idx][1] = batchEpoch;
                execWarmupDecisionState[idx][2] = contentionPolicy;
                execWarmupDecisionState[idx][3] = bodyPolicy;
                execWarmupDecisionState[idx][4] = contention;
                execWarmupSmoothedBodyCost[idx] = smoothedBodyCost;
            }
            execTailDecisionState[idx][0] = cycleEpoch;
            execTailDecisionState[idx][1] = batchEpoch;
            execTailDecisionState[idx][2] = contentionPolicy;
            execTailDecisionState[idx][3] = bodyPolicy;
            execTailDecisionState[idx][4] = contention;
            execTailSmoothedBodyCost[idx] = smoothedBodyCost;

            execBranchDecisionTotal[contentionPolicy][bodyPolicy]++;
        }

        public void align() {
            if (aligned) {
                return;
            }
            long[][] longAligner = new long[rawSampleLimit][];
            align(longAligner, cycleStartTailState, cycleStartObservations);
            align(longAligner, batchProgressTailState, batchProgressObservations);
            align(longAligner, batchCompleteTailState, batchCompleteObservations);
            align(longAligner, rawBodyCostTailState, rawBodyCostObservations);
            align(longAligner, idleTailDecisionState, idleDecisionObservations);
            align(longAligner, execTailDecisionState, execDecisionObservations);

            double[] doubleAligner = new double[rawSampleLimit];
            align(doubleAligner, cycleStartTailThroughput, cycleStartObservations);
            align(doubleAligner, batchProgressTailAvgServiceTime, batchProgressObservations);
            align(doubleAligner, batchCompleteTailAvgServiceTime, batchCompleteObservations);
            align(doubleAligner, batchCompleteTailThroughput, batchCompleteObservations);
            align(doubleAligner, idleTailSmoothedBodyCost, idleDecisionObservations);
            align(doubleAligner, execTailSmoothedBodyCost, execDecisionObservations);
            aligned = true;
        }

        void align(long[][] aligner, long[][] target, long count) {
            if (count == target.length || count == 0) {
                return;
            }
            // Fill aligner newest -> oldest
            int idx = 0;
            for (int i = 0; i < target.length && count > 0; i++) {
                aligner[idx++] = target[(int) (--count & this.mask)];
            }

            // Fill target oldest -> newest
            int len = idx;
            for (int i = 0; i < len; i++) {
                target[i] = aligner[idx--];
            }
        }

        void align(double[] aligner, double[] target, long count) {
            if (count == target.length || count == 0) {
                return;
            }
            // Fill aligner newest -> oldest
            int idx = 0;
            for (int i = 0; i < target.length && count > 0; i++) {
                aligner[idx++] = target[(int) (--count & this.mask)];
            }

            // Fill target oldest -> newest
            int len = idx;
            for (int i = 0; i < len; i++) {
                target[i] = aligner[idx--];
            }
        }
    }
}
