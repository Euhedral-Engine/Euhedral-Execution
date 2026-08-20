package calibration.infra;

import calibration.config.CalibrationBenchmarkConfig;
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
                new PaddedAtomicReferenceArray<>(SystemInfo.getMaxCoreId() + 1, true, true);
        BitSet cores = SystemInfo.getCoreSet();
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
            long productiveHandleCount,
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
                productiveHandleCount,
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
            long productiveHandleCount,
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
                cycleEpoch,
                batchEpoch,
                upstreamCount,
                registeredWorkers,
                productiveHandleCount,
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
            long productiveHandleCount,
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
                productiveHandleCount,
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
        public final long[][] cycleStartSteadyStateState;
        public final double[] cycleStartSteadyStateThroughput;

        public final long[][] batchProgressWarmupState;
        public final double[] batchProgressWarmupAvgServiceTime;
        public final long[][] batchProgressSteadyStateState;
        public final double[] batchProgressSteadyStateAvgServiceTime;

        public final long[][] batchCompleteWarmupState;
        public final double[] batchCompleteWarmupAvgServiceTime;
        public final double[] batchCompleteWarmupThroughput;
        public final long[][] batchCompleteSteadyStateState;
        public final double[] batchCompleteSteadyStateAvgServiceTime;
        public final double[] batchCompleteSteadyStateThroughput;

        public final long[][] rawBodyCostWarmupState;
        public final long[][] rawBodyCostSteadyStateState;

        public final long[][] idleBranchDecisionTotal =
                new long[calibration.statistics.Band.GRID_SIZE][calibration.statistics.Band.GRID_SIZE];
        public final long[][] idleWarmupDecisionState;
        public final double[] idleWarmupSmoothedBodyCost;
        public final long[][] idleSteadyStateDecisionState;
        public final double[] idleSteadyStateSmoothedBodyCost;

        public final long[][] execBranchDecisionTotal =
                new long[calibration.statistics.Band.GRID_SIZE][calibration.statistics.Band.GRID_SIZE];
        public final long[][] execWarmupDecisionState;
        public final double[] execWarmupSmoothedBodyCost;
        public final long[][] execSteadyStateDecisionState;
        public final double[] execSteadyStateSmoothedBodyCost;

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

            this.cycleStartWarmupState = new long[rawSampleLimit][9];
            this.cycleStartWarmupThroughput = new double[rawSampleLimit];
            this.cycleStartSteadyStateState = new long[rawSampleLimit][9];
            this.cycleStartSteadyStateThroughput = new double[rawSampleLimit];

            this.batchProgressWarmupState = new long[rawSampleLimit][7];
            this.batchProgressWarmupAvgServiceTime = new double[rawSampleLimit];
            this.batchProgressSteadyStateState = new long[rawSampleLimit][7];
            this.batchProgressSteadyStateAvgServiceTime = new double[rawSampleLimit];

            this.batchCompleteWarmupState = new long[rawSampleLimit][7];
            this.batchCompleteWarmupAvgServiceTime = new double[rawSampleLimit];
            this.batchCompleteWarmupThroughput = new double[rawSampleLimit];
            this.batchCompleteSteadyStateState = new long[rawSampleLimit][7];
            this.batchCompleteSteadyStateAvgServiceTime = new double[rawSampleLimit];
            this.batchCompleteSteadyStateThroughput = new double[rawSampleLimit];

            this.rawBodyCostWarmupState = new long[rawSampleLimit][3];
            this.rawBodyCostSteadyStateState = new long[rawSampleLimit][3];

            this.idleWarmupDecisionState = new long[rawSampleLimit][5];
            this.idleWarmupSmoothedBodyCost = new double[rawSampleLimit];
            this.idleSteadyStateDecisionState = new long[rawSampleLimit][5];
            this.idleSteadyStateSmoothedBodyCost = new double[rawSampleLimit];

            this.execWarmupDecisionState = new long[rawSampleLimit][5];
            this.execWarmupSmoothedBodyCost = new double[rawSampleLimit];
            this.execSteadyStateDecisionState = new long[rawSampleLimit][5];
            this.execSteadyStateSmoothedBodyCost = new double[rawSampleLimit];
        }

        public void recordCycleStart(
                long cycleEpoch,
                long batchEpoch,
                long completed,
                long batchSize,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double throughput) {
            recordCycleStart(
                    cycleEpoch,
                    batchEpoch,
                    completed,
                    batchSize,
                    upstreamCount,
                    registeredWorkers,
                    upstreamCount,
                    workerRank,
                    contention,
                    throughput);
        }

        public void recordCycleStart(
                long cycleEpoch,
                long batchEpoch,
                long completed,
                long batchSize,
                long upstreamCount,
                int registeredWorkers,
                long productiveHandleCount,
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
                cycleStartWarmupState[idx][8] = productiveHandleCount;
                cycleStartWarmupThroughput[idx] = throughput;
            }
            cycleStartSteadyStateState[idx][0] = cycleEpoch;
            cycleStartSteadyStateState[idx][1] = batchEpoch;
            cycleStartSteadyStateState[idx][2] = completed;
            cycleStartSteadyStateState[idx][3] = batchSize;
            cycleStartSteadyStateState[idx][4] = upstreamCount;
            cycleStartSteadyStateState[idx][5] = registeredWorkers;
            cycleStartSteadyStateState[idx][6] = workerRank;
            cycleStartSteadyStateState[idx][7] = contention;
            cycleStartSteadyStateState[idx][8] = productiveHandleCount;
            cycleStartSteadyStateThroughput[idx] = throughput;
        }

        public void recordBatchProgress(
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double avgServiceTime) {
            recordBatchProgress(
                    cycleEpoch,
                    batchEpoch,
                    upstreamCount,
                    registeredWorkers,
                    upstreamCount,
                    workerRank,
                    contention,
                    avgServiceTime);
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
            int idx = (int) (batchProgressObservations & this.mask);
            if (batchProgressObservations++ < rawSampleLimit) {
                batchProgressWarmupState[idx][0] = cycleEpoch;
                batchProgressWarmupState[idx][1] = batchEpoch;
                batchProgressWarmupState[idx][2] = upstreamCount;
                batchProgressWarmupState[idx][3] = registeredWorkers;
                batchProgressWarmupState[idx][4] = workerRank;
                batchProgressWarmupState[idx][5] = contention;
                batchProgressWarmupState[idx][6] = productiveHandleCount;
                batchProgressWarmupAvgServiceTime[idx] = avgServiceTime;
            }
            batchProgressSteadyStateState[idx][0] = cycleEpoch;
            batchProgressSteadyStateState[idx][1] = batchEpoch;
            batchProgressSteadyStateState[idx][2] = upstreamCount;
            batchProgressSteadyStateState[idx][3] = registeredWorkers;
            batchProgressSteadyStateState[idx][4] = workerRank;
            batchProgressSteadyStateState[idx][5] = contention;
            batchProgressSteadyStateState[idx][6] = productiveHandleCount;
            batchProgressSteadyStateAvgServiceTime[idx] = avgServiceTime;
        }

        public void recordBatchComplete(
                long cycleEpoch,
                long batchEpoch,
                long upstreamCount,
                int registeredWorkers,
                int workerRank,
                long contention,
                double avgServiceTime,
                double throughput) {
            recordBatchComplete(
                    cycleEpoch,
                    batchEpoch,
                    upstreamCount,
                    registeredWorkers,
                    upstreamCount,
                    workerRank,
                    contention,
                    avgServiceTime,
                    throughput);
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
            int idx = (int) (batchCompleteObservations & this.mask);
            if (batchCompleteObservations++ < rawSampleLimit) {
                batchCompleteWarmupState[idx][0] = cycleEpoch;
                batchCompleteWarmupState[idx][1] = batchEpoch;
                batchCompleteWarmupState[idx][2] = upstreamCount;
                batchCompleteWarmupState[idx][3] = registeredWorkers;
                batchCompleteWarmupState[idx][4] = workerRank;
                batchCompleteWarmupState[idx][5] = contention;
                batchCompleteWarmupState[idx][6] = productiveHandleCount;
                batchCompleteWarmupAvgServiceTime[idx] = avgServiceTime;
                batchCompleteWarmupThroughput[idx] = throughput;
            }
            batchCompleteSteadyStateState[idx][0] = cycleEpoch;
            batchCompleteSteadyStateState[idx][1] = batchEpoch;
            batchCompleteSteadyStateState[idx][2] = upstreamCount;
            batchCompleteSteadyStateState[idx][3] = registeredWorkers;
            batchCompleteSteadyStateState[idx][4] = workerRank;
            batchCompleteSteadyStateState[idx][5] = contention;
            batchCompleteSteadyStateState[idx][6] = productiveHandleCount;
            batchCompleteSteadyStateAvgServiceTime[idx] = avgServiceTime;
            batchCompleteSteadyStateThroughput[idx] = throughput;
        }

        public void recordRawBodyCost(long cycleEpoch, long batchEpoch, long rawBodyCost) {
            int idx = (int) (rawBodyCostObservations & this.mask);
            if (rawBodyCostObservations < rawSampleLimit) {
                rawBodyCostWarmupState[idx][0] = cycleEpoch;
                rawBodyCostWarmupState[idx][1] = batchEpoch;
                rawBodyCostWarmupState[idx][2] = rawBodyCost;
            }
            rawBodyCostSteadyStateState[idx][0] = cycleEpoch;
            rawBodyCostSteadyStateState[idx][1] = batchEpoch;
            rawBodyCostSteadyStateState[idx][2] = rawBodyCost;
            rawBodyCostTotal += rawBodyCost;
            rawBodyCostObservations++;
        }

        public void recordIdle(
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
            idleSteadyStateDecisionState[idx][0] = cycleEpoch;
            idleSteadyStateDecisionState[idx][1] = batchEpoch;
            idleSteadyStateDecisionState[idx][2] = contentionPolicy;
            idleSteadyStateDecisionState[idx][3] = bodyPolicy;
            idleSteadyStateDecisionState[idx][4] = contention;
            idleSteadyStateSmoothedBodyCost[idx] = smoothedBodyCost;

            idleBranchDecisionTotal[contentionPolicy][bodyPolicy]++;
        }

        public void recordExec(
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
            execSteadyStateDecisionState[idx][0] = cycleEpoch;
            execSteadyStateDecisionState[idx][1] = batchEpoch;
            execSteadyStateDecisionState[idx][2] = contentionPolicy;
            execSteadyStateDecisionState[idx][3] = bodyPolicy;
            execSteadyStateDecisionState[idx][4] = contention;
            execSteadyStateSmoothedBodyCost[idx] = smoothedBodyCost;

            execBranchDecisionTotal[contentionPolicy][bodyPolicy]++;
        }

        public void align() {
            if (aligned) {
                return;
            }
            long[][] longAligner = new long[rawSampleLimit][];
            align(longAligner, cycleStartSteadyStateState, cycleStartObservations);
            align(longAligner, batchProgressSteadyStateState, batchProgressObservations);
            align(longAligner, batchCompleteSteadyStateState, batchCompleteObservations);
            align(longAligner, rawBodyCostSteadyStateState, rawBodyCostObservations);
            align(longAligner, idleSteadyStateDecisionState, idleDecisionObservations);
            align(longAligner, execSteadyStateDecisionState, execDecisionObservations);

            double[] doubleAligner = new double[rawSampleLimit];
            align(doubleAligner, cycleStartSteadyStateThroughput, cycleStartObservations);
            align(doubleAligner, batchProgressSteadyStateAvgServiceTime, batchProgressObservations);
            align(doubleAligner, batchCompleteSteadyStateAvgServiceTime, batchCompleteObservations);
            align(doubleAligner, batchCompleteSteadyStateThroughput, batchCompleteObservations);
            align(doubleAligner, idleSteadyStateSmoothedBodyCost, idleDecisionObservations);
            align(doubleAligner, execSteadyStateSmoothedBodyCost, execDecisionObservations);
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
                target[i] = aligner[--idx];
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
                target[i] = aligner[--idx];
            }
        }
    }
}
