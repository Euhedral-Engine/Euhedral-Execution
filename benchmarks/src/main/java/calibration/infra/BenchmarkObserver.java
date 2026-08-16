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

    public void stopObserving() {
        this.metrics.set(null);
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
                core,
                socket,
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
                core,
                socket,
                cycleEpoch,
                batchEpoch,
                upstreamCount,
                registeredWorkers,
                workerRank,
                contention,
                avgServiceTime);
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
                core,
                socket,
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
        coreMetrics.recordRawBodyCost(core, socket, cycleEpoch, batchEpoch, rawBodyCost);
    }

    @Override
    protected void idleBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            double smoothedBodyCost) {
        if (!this.config.observeIdleDecision() || !Double.isFinite(smoothedBodyCost)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordIdle(core, socket, cycleEpoch, batchEpoch, contentionPolicy, bodyPolicy, smoothedBodyCost);
    }

    @Override
    protected void execBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            double smoothedBodyCost) {
        if (!this.config.observeExecDecision() || !Double.isFinite(smoothedBodyCost)) {
            return;
        }

        HighSpeedMetrics coreMetrics = getCoreMetrics(core);
        if (coreMetrics == null) {
            return;
        }
        coreMetrics.recordExec(core, socket, cycleEpoch, batchEpoch, contentionPolicy, bodyPolicy, smoothedBodyCost);
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

        public final long[][] rawBodyCostState;

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

        public HighSpeedMetrics(int rawSampleLimit) {
            rawSampleLimit = Integer.highestOneBit((rawSampleLimit - 1) << 1);
            this.rawSampleLimit = rawSampleLimit;
            this.mask = rawSampleLimit - 1;

            this.cycleStartWarmupState = new long[rawSampleLimit][10];
            this.cycleStartWarmupThroughput = new double[rawSampleLimit];
            this.cycleStartTailState = new long[rawSampleLimit][10];
            this.cycleStartTailThroughput = new double[rawSampleLimit];

            this.batchProgressWarmupState = new long[rawSampleLimit][8];
            this.batchProgressWarmupAvgServiceTime = new double[rawSampleLimit];
            this.batchProgressTailState = new long[rawSampleLimit][8];
            this.batchProgressTailAvgServiceTime = new double[rawSampleLimit];

            this.batchCompleteWarmupState = new long[rawSampleLimit][8];
            this.batchCompleteWarmupAvgServiceTime = new double[rawSampleLimit];
            this.batchCompleteWarmupThroughput = new double[rawSampleLimit];
            this.batchCompleteTailState = new long[rawSampleLimit][8];
            this.batchCompleteTailAvgServiceTime = new double[rawSampleLimit];
            this.batchCompleteTailThroughput = new double[rawSampleLimit];

            this.rawBodyCostState = new long[5][rawSampleLimit];

            this.idleWarmupDecisionState = new long[rawSampleLimit][6];
            this.idleWarmupSmoothedBodyCost = new double[rawSampleLimit];
            this.idleTailDecisionState = new long[rawSampleLimit][6];
            this.idleTailSmoothedBodyCost = new double[rawSampleLimit];

            this.execWarmupDecisionState = new long[rawSampleLimit][6];
            this.execWarmupSmoothedBodyCost = new double[rawSampleLimit];
            this.execTailDecisionState = new long[rawSampleLimit][6];
            this.execTailSmoothedBodyCost = new double[rawSampleLimit];
        }

        void recordCycleStart(
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
            int idx = (int) (cycleStartObservations & this.mask);
            if (cycleStartObservations++ < rawSampleLimit) {
                cycleStartWarmupState[idx][0] = core;
                cycleStartWarmupState[idx][1] = socket;
                cycleStartWarmupState[idx][2] = cycleEpoch;
                cycleStartWarmupState[idx][3] = batchEpoch;
                cycleStartWarmupState[idx][4] = completed;
                cycleStartWarmupState[idx][5] = batchSize;
                cycleStartWarmupState[idx][6] = upstreamCount;
                cycleStartWarmupState[idx][7] = registeredWorkers;
                cycleStartWarmupState[idx][8] = workerRank;
                cycleStartWarmupState[idx][9] = contention;
                cycleStartWarmupThroughput[idx] = throughput;
            }
            cycleStartTailState[idx][0] = core;
            cycleStartTailState[idx][1] = socket;
            cycleStartTailState[idx][2] = cycleEpoch;
            cycleStartTailState[idx][3] = batchEpoch;
            cycleStartTailState[idx][4] = completed;
            cycleStartTailState[idx][5] = batchSize;
            cycleStartTailState[idx][6] = upstreamCount;
            cycleStartTailState[idx][7] = registeredWorkers;
            cycleStartTailState[idx][8] = workerRank;
            cycleStartTailState[idx][9] = contention;
            cycleStartTailThroughput[idx] = throughput;
        }

        void recordBatchProgress(
                int core,
                int socket,
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double avgServiceTime) {
            int idx = (int) (batchProgressObservations & this.mask);
            if (batchProgressObservations++ < rawSampleLimit) {
                batchProgressWarmupState[idx][0] = core;
                batchProgressWarmupState[idx][1] = socket;
                batchProgressWarmupState[idx][2] = cycleEpoch;
                batchProgressWarmupState[idx][3] = batchEpoch;
                batchProgressWarmupState[idx][4] = upstreamCount;
                batchProgressWarmupState[idx][5] = registeredWorkers;
                batchProgressWarmupState[idx][6] = workerRank;
                batchProgressWarmupState[idx][7] = contention;
                batchProgressWarmupAvgServiceTime[idx] = avgServiceTime;
            }
            batchProgressTailState[idx][0] = core;
            batchProgressTailState[idx][1] = socket;
            batchProgressTailState[idx][2] = cycleEpoch;
            batchProgressTailState[idx][3] = batchEpoch;
            batchProgressTailState[idx][4] = upstreamCount;
            batchProgressTailState[idx][5] = registeredWorkers;
            batchProgressTailState[idx][6] = workerRank;
            batchProgressTailState[idx][7] = contention;
            batchProgressTailAvgServiceTime[idx] = avgServiceTime;
        }

        void recordBatchComplete(
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
            int idx = (int) (batchCompleteObservations & this.mask);
            if (batchCompleteObservations++ < rawSampleLimit) {
                batchCompleteWarmupState[idx][0] = core;
                batchCompleteWarmupState[idx][1] = socket;
                batchCompleteWarmupState[idx][2] = cycleEpoch;
                batchCompleteWarmupState[idx][3] = batchEpoch;
                batchCompleteWarmupState[idx][4] = upstreamCount;
                batchCompleteWarmupState[idx][5] = registeredWorkers;
                batchCompleteWarmupState[idx][6] = workerRank;
                batchCompleteWarmupState[idx][7] = contention;
                batchCompleteWarmupAvgServiceTime[idx] = avgServiceTime;
                batchCompleteWarmupThroughput[idx] = throughput;
            }
            batchCompleteTailState[idx][0] = core;
            batchCompleteTailState[idx][1] = socket;
            batchCompleteTailState[idx][2] = cycleEpoch;
            batchCompleteTailState[idx][3] = batchEpoch;
            batchCompleteTailState[idx][4] = upstreamCount;
            batchCompleteTailState[idx][5] = registeredWorkers;
            batchCompleteTailState[idx][6] = workerRank;
            batchCompleteTailState[idx][7] = contention;
            batchCompleteTailAvgServiceTime[idx] = avgServiceTime;
            batchCompleteTailThroughput[idx] = throughput;
        }

        void recordRawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost) {
            int idx = (int) (rawBodyCostObservations & this.mask);
            rawBodyCostState[0][idx] = core;
            rawBodyCostState[1][idx] = socket;
            rawBodyCostState[2][idx] = cycleEpoch;
            rawBodyCostState[3][idx] = batchEpoch;
            rawBodyCostState[4][idx] = rawBodyCost;
            rawBodyCostTotal += rawBodyCost;
            rawBodyCostObservations++;
        }

        void recordIdle(
                int core,
                int socket,
                long cycleEpoch,
                long batchEpoch,
                int contentionPolicy,
                int bodyPolicy,
                double smoothedBodyCost) {
            int idx = (int) (idleDecisionObservations & this.mask);
            if (idleDecisionObservations++ < rawSampleLimit) {
                idleWarmupDecisionState[idx][0] = core;
                idleWarmupDecisionState[idx][1] = socket;
                idleWarmupDecisionState[idx][2] = cycleEpoch;
                idleWarmupDecisionState[idx][3] = batchEpoch;
                idleWarmupDecisionState[idx][4] = contentionPolicy;
                idleWarmupDecisionState[idx][5] = bodyPolicy;
                idleWarmupSmoothedBodyCost[idx] = smoothedBodyCost;
            }
            idleTailDecisionState[idx][0] = core;
            idleTailDecisionState[idx][1] = socket;
            idleTailDecisionState[idx][2] = cycleEpoch;
            idleTailDecisionState[idx][3] = batchEpoch;
            idleTailDecisionState[idx][4] = contentionPolicy;
            idleTailDecisionState[idx][5] = bodyPolicy;
            idleTailSmoothedBodyCost[idx] = smoothedBodyCost;

            idleBranchDecisionTotal[contentionPolicy][bodyPolicy]++;
        }

        void recordExec(
                int core,
                int socket,
                long cycleEpoch,
                long batchEpoch,
                int contentionPolicy,
                int bodyPolicy,
                double smoothedBodyCost) {
            int idx = (int) (execDecisionObservations & this.mask);
            if (execDecisionObservations++ < rawSampleLimit) {
                execWarmupDecisionState[idx][0] = core;
                execWarmupDecisionState[idx][1] = socket;
                execWarmupDecisionState[idx][2] = cycleEpoch;
                execWarmupDecisionState[idx][3] = batchEpoch;
                execWarmupDecisionState[idx][4] = contentionPolicy;
                execWarmupDecisionState[idx][5] = bodyPolicy;
                execWarmupSmoothedBodyCost[idx] = smoothedBodyCost;
            }
            execTailDecisionState[idx][0] = core;
            execTailDecisionState[idx][1] = socket;
            execTailDecisionState[idx][2] = cycleEpoch;
            execTailDecisionState[idx][3] = batchEpoch;
            execTailDecisionState[idx][4] = contentionPolicy;
            execTailDecisionState[idx][5] = bodyPolicy;
            execTailSmoothedBodyCost[idx] = smoothedBodyCost;

            execBranchDecisionTotal[contentionPolicy][bodyPolicy]++;
        }
    }
}
