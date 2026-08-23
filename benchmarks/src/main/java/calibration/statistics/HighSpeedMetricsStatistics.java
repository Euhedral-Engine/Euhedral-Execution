package calibration.statistics;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.CycleStartScalars;
import calibration.statistics.iteration.CycleStartStatistics;
import calibration.statistics.iteration.DecisionScalars;
import calibration.statistics.iteration.DecisionStatistics;
import calibration.statistics.iteration.OccupancySummary;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.SystemIterationResult;
import calibration.statistics.iteration.TransitionAnalysis;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Authoritative calculation engine for detached HighSpeedMetrics.
///
/// Converts captured raw observation buffers and exact branch counters into immutable
/// descriptive, quantile, occupancy, transition, vector-field, and correlation results
/// for individual cores, within-iteration whole-system views, and authoritative whole-fork summaries.
public final class HighSpeedMetricsStatistics {

    private HighSpeedMetricsStatistics() {}

    private static double productiveHandleRatio(double productiveHandles, double registeredWorkers) {
        return registeredWorkers > 0.0 ? productiveHandles / registeredWorkers : 0.0;
    }

    /// Calculates statistics for a single detached metric instance.
    public static @NonNull CoreIterationResult calculate(int core, @Nullable HighSpeedMetrics metrics) {
        return calculate(0, core, metrics);
    }

    /// Calculates statistics for a single detached metric instance with iteration context.
    public static @NonNull CoreIterationResult calculate(
            int iterationIndex, int core, @Nullable HighSpeedMetrics metrics) {
        if (metrics == null) {
            return CoreIterationResult.empty(iterationIndex, core);
        }

        metrics.align();
        List<HighSpeedMetrics> list = List.of(metrics);

        CycleStartStatistics cycleStart = calculateCycleStart(list);
        BatchProgressStatistics batchProgress = calculateBatchProgress(list);
        BatchCompleteStatistics batchComplete = calculateBatchComplete(list);
        RawBodyCostStatistics rawBodyCost = calculateRawBodyCost(list);
        DecisionStatistics idleDecisions = calculateIdleDecisions(list);
        DecisionStatistics execDecisions = calculateExecDecisions(list);

        double centroidDistance = OccupancySummary.distance(
                idleDecisions.occupancy().summary(), execDecisions.occupancy().summary());

        return new CoreIterationResult(
                iterationIndex,
                core,
                cycleStart.totalObservations(),
                batchProgress.totalObservations(),
                batchComplete.totalObservations(),
                rawBodyCost.totalObservations(),
                idleDecisions.totalObservations(),
                execDecisions.totalObservations(),
                cycleStart,
                batchProgress,
                batchComplete,
                rawBodyCost,
                idleDecisions,
                execDecisions,
                centroidDistance);
    }

    /// Calculates whole-system aggregated statistics across participating cores for one iteration.
    public static @NonNull SystemIterationResult calculateSystem(
            int iterationIndex, @Nullable Collection<HighSpeedMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return SystemIterationResult.empty(iterationIndex, 0);
        }

        List<HighSpeedMetrics> validMetrics = new ArrayList<>(metrics.size());
        for (HighSpeedMetrics m : metrics) {
            if (m != null) {
                m.align();
                validMetrics.add(m);
            }
        }

        if (validMetrics.isEmpty()) {
            return SystemIterationResult.empty(iterationIndex, 0);
        }

        CycleStartStatistics cycleStart = calculateCycleStart(validMetrics);
        BatchProgressStatistics batchProgress = calculateBatchProgress(validMetrics);
        BatchCompleteStatistics batchComplete = calculateBatchComplete(validMetrics);
        RawBodyCostStatistics rawBodyCost = calculateRawBodyCost(validMetrics);
        DecisionStatistics idleDecisions = calculateIdleDecisions(validMetrics);
        DecisionStatistics execDecisions = calculateExecDecisions(validMetrics);

        double centroidDistance = OccupancySummary.distance(
                idleDecisions.occupancy().summary(), execDecisions.occupancy().summary());

        return new SystemIterationResult(
                iterationIndex,
                validMetrics.size(),
                cycleStart.totalObservations(),
                batchProgress.totalObservations(),
                batchComplete.totalObservations(),
                rawBodyCost.totalObservations(),
                idleDecisions.totalObservations(),
                execDecisions.totalObservations(),
                cycleStart,
                batchProgress,
                batchComplete,
                rawBodyCost,
                idleDecisions,
                execDecisions,
                centroidDistance);
    }

    /// Calculates whole-fork aggregated statistics across all participating cores and measurement iterations.
    public static @NonNull SystemForkResult calculateSystemFork(
            @Nullable Collection<? extends Collection<HighSpeedMetrics>> measurementIterations) {
        return calculateSystemFork(0, measurementIterations);
    }

    /// Calculates whole-fork aggregated statistics across all participating cores and measurement iterations
    /// with explicit fork index.
    public static @NonNull SystemForkResult calculateSystemFork(
            int forkIndex, @Nullable Collection<? extends Collection<HighSpeedMetrics>> measurementIterations) {
        if (measurementIterations == null || measurementIterations.isEmpty()) {
            return SystemForkResult.empty(forkIndex, 0, 0);
        }

        List<HighSpeedMetrics> allMetrics = new ArrayList<>();
        int measurementIterationCount = 0;
        int maxParticipatingCores = 0;
        for (Collection<HighSpeedMetrics> iterMetrics : measurementIterations) {
            if (iterMetrics == null || iterMetrics.isEmpty()) {
                continue;
            }
            int iterCoreCount = 0;
            for (HighSpeedMetrics m : iterMetrics) {
                if (m != null) {
                    m.align();
                    allMetrics.add(m);
                    iterCoreCount++;
                }
            }
            if (iterCoreCount > 0) {
                measurementIterationCount++;
                if (iterCoreCount > maxParticipatingCores) {
                    maxParticipatingCores = iterCoreCount;
                }
            }
        }

        if (allMetrics.isEmpty()) {
            return SystemForkResult.empty(forkIndex, 0, 0);
        }

        CycleStartStatistics cycleStart = calculateCycleStart(allMetrics);
        BatchProgressStatistics batchProgress = calculateBatchProgress(allMetrics);
        BatchCompleteStatistics batchComplete = calculateBatchComplete(allMetrics);
        RawBodyCostStatistics rawBodyCost = calculateRawBodyCost(allMetrics);
        DecisionStatistics idleDecisions = calculateIdleDecisions(allMetrics);
        DecisionStatistics execDecisions = calculateExecDecisions(allMetrics);

        double centroidDistance = OccupancySummary.distance(
                idleDecisions.occupancy().summary(), execDecisions.occupancy().summary());

        return new SystemForkResult(
                forkIndex,
                measurementIterationCount,
                maxParticipatingCores,
                cycleStart.totalObservations(),
                batchProgress.totalObservations(),
                batchComplete.totalObservations(),
                rawBodyCost.totalObservations(),
                idleDecisions.totalObservations(),
                execDecisions.totalObservations(),
                cycleStart,
                batchProgress,
                batchComplete,
                rawBodyCost,
                idleDecisions,
                execDecisions,
                centroidDistance);
    }

    private static CycleStartStatistics calculateCycleStart(List<HighSpeedMetrics> metricsList) {
        long total = 0L;
        int hTotalLen = 0;
        int tTotalLen = 0;
        int cTotalLen = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.cycleStartObservations;
            total += obs;
            if (obs > 0L) {
                int limit = m.rawSampleLimit;
                int hLen = (int) Math.min(obs, limit);
                int tLen = (int) Math.min(obs, limit);
                int cLen = obs <= hLen ? hLen : (hLen + tLen);
                hTotalLen += hLen;
                tTotalLen += tLen;
                cTotalLen += cLen;
            }
        }

        if (total == 0L) {
            return CycleStartStatistics.EMPTY;
        }

        double[] hCompleted = new double[hTotalLen];
        double[] hBatchSize = new double[hTotalLen];
        double[] hUpstream = new double[hTotalLen];
        double[] hWorkers = new double[hTotalLen];
        double[] hProductive = new double[hTotalLen];
        double[] hProductiveRatio = new double[hTotalLen];
        double[] hRank = new double[hTotalLen];
        double[] hContention = new double[hTotalLen];
        double[] hThroughput = new double[hTotalLen];
        double[][] hData = hTotalLen >= 2 ? new double[hTotalLen][9] : new double[0][0];

        double[] tCompleted = new double[tTotalLen];
        double[] tBatchSize = new double[tTotalLen];
        double[] tUpstream = new double[tTotalLen];
        double[] tWorkers = new double[tTotalLen];
        double[] tProductive = new double[tTotalLen];
        double[] tProductiveRatio = new double[tTotalLen];
        double[] tRank = new double[tTotalLen];
        double[] tContention = new double[tTotalLen];
        double[] tThroughput = new double[tTotalLen];
        double[][] tData = tTotalLen >= 2 ? new double[tTotalLen][9] : new double[0][0];

        double[] cCompleted = new double[cTotalLen];
        double[] cBatchSize = new double[cTotalLen];
        double[] cUpstream = new double[cTotalLen];
        double[] cWorkers = new double[cTotalLen];
        double[] cProductive = new double[cTotalLen];
        double[] cProductiveRatio = new double[cTotalLen];
        double[] cRank = new double[cTotalLen];
        double[] cContention = new double[cTotalLen];
        double[] cThroughput = new double[cTotalLen];
        double[][] cData = cTotalLen >= 2 ? new double[cTotalLen][9] : new double[0][0];

        int hOffset = 0;
        int tOffset = 0;
        int cOffset = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.cycleStartObservations;
            if (obs == 0L) {
                continue;
            }
            int limit = m.rawSampleLimit;
            int hLen = (int) Math.min(obs, limit);
            int tLen = (int) Math.min(obs, limit);

            // Head
            for (int i = 0; i < hLen; i++) {
                int idx = hOffset + i;
                double comp = (double) m.cycleStartWarmupState[i][2];
                double bs = (double) m.cycleStartWarmupState[i][3];
                double up = (double) m.cycleStartWarmupState[i][4];
                double wrk = (double) m.cycleStartWarmupState[i][5];
                double rk = (double) m.cycleStartWarmupState[i][6];
                double cnt = (double) m.cycleStartWarmupState[i][7];
                double productive = (double) m.cycleStartWarmupState[i][8];
                double productiveRatio = productiveHandleRatio(productive, wrk);
                double tp = m.cycleStartWarmupThroughput[i];

                hCompleted[idx] = comp;
                hBatchSize[idx] = bs;
                hUpstream[idx] = up;
                hWorkers[idx] = wrk;
                hProductive[idx] = productive;
                hProductiveRatio[idx] = productiveRatio;
                hRank[idx] = rk;
                hContention[idx] = cnt;
                hThroughput[idx] = tp;

                if (hData.length > 0) {
                    hData[idx][0] = comp;
                    hData[idx][1] = bs;
                    hData[idx][2] = up;
                    hData[idx][3] = wrk;
                    hData[idx][4] = productive;
                    hData[idx][5] = productiveRatio;
                    hData[idx][6] = rk;
                    hData[idx][7] = cnt;
                    hData[idx][8] = tp;
                }
            }
            hOffset += hLen;

            // SteadyState
            for (int i = 0; i < tLen; i++) {
                int idx = tOffset + i;
                double comp = (double) m.cycleStartSteadyStateState[i][2];
                double bs = (double) m.cycleStartSteadyStateState[i][3];
                double up = (double) m.cycleStartSteadyStateState[i][4];
                double wrk = (double) m.cycleStartSteadyStateState[i][5];
                double rk = (double) m.cycleStartSteadyStateState[i][6];
                double cnt = (double) m.cycleStartSteadyStateState[i][7];
                double productive = (double) m.cycleStartSteadyStateState[i][8];
                double productiveRatio = productiveHandleRatio(productive, wrk);
                double tp = m.cycleStartSteadyStateThroughput[i];

                tCompleted[idx] = comp;
                tBatchSize[idx] = bs;
                tUpstream[idx] = up;
                tWorkers[idx] = wrk;
                tProductive[idx] = productive;
                tProductiveRatio[idx] = productiveRatio;
                tRank[idx] = rk;
                tContention[idx] = cnt;
                tThroughput[idx] = tp;

                if (tData.length > 0) {
                    tData[idx][0] = comp;
                    tData[idx][1] = bs;
                    tData[idx][2] = up;
                    tData[idx][3] = wrk;
                    tData[idx][4] = productive;
                    tData[idx][5] = productiveRatio;
                    tData[idx][6] = rk;
                    tData[idx][7] = cnt;
                    tData[idx][8] = tp;
                }
            }
            tOffset += tLen;

            // Combined
            if (obs <= hLen) {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double comp = (double) m.cycleStartWarmupState[i][2];
                    double bs = (double) m.cycleStartWarmupState[i][3];
                    double up = (double) m.cycleStartWarmupState[i][4];
                    double wrk = (double) m.cycleStartWarmupState[i][5];
                    double rk = (double) m.cycleStartWarmupState[i][6];
                    double cnt = (double) m.cycleStartWarmupState[i][7];
                    double productive = (double) m.cycleStartWarmupState[i][8];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double tp = m.cycleStartWarmupThroughput[i];

                    cCompleted[idx] = comp;
                    cBatchSize[idx] = bs;
                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cThroughput[idx] = tp;

                    if (cData.length > 0) {
                        cData[idx][0] = comp;
                        cData[idx][1] = bs;
                        cData[idx][2] = up;
                        cData[idx][3] = wrk;
                        cData[idx][4] = productive;
                        cData[idx][5] = productiveRatio;
                        cData[idx][6] = rk;
                        cData[idx][7] = cnt;
                        cData[idx][8] = tp;
                    }
                }
                cOffset += hLen;
            } else {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double comp = (double) m.cycleStartWarmupState[i][2];
                    double bs = (double) m.cycleStartWarmupState[i][3];
                    double up = (double) m.cycleStartWarmupState[i][4];
                    double wrk = (double) m.cycleStartWarmupState[i][5];
                    double rk = (double) m.cycleStartWarmupState[i][6];
                    double cnt = (double) m.cycleStartWarmupState[i][7];
                    double productive = (double) m.cycleStartWarmupState[i][8];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double tp = m.cycleStartWarmupThroughput[i];

                    cCompleted[idx] = comp;
                    cBatchSize[idx] = bs;
                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cThroughput[idx] = tp;

                    if (cData.length > 0) {
                        cData[idx][0] = comp;
                        cData[idx][1] = bs;
                        cData[idx][2] = up;
                        cData[idx][3] = wrk;
                        cData[idx][4] = productive;
                        cData[idx][5] = productiveRatio;
                        cData[idx][6] = rk;
                        cData[idx][7] = cnt;
                        cData[idx][8] = tp;
                    }
                }
                cOffset += hLen;
                for (int i = 0; i < tLen; i++) {
                    int idx = cOffset + i;
                    double comp = (double) m.cycleStartSteadyStateState[i][2];
                    double bs = (double) m.cycleStartSteadyStateState[i][3];
                    double up = (double) m.cycleStartSteadyStateState[i][4];
                    double wrk = (double) m.cycleStartSteadyStateState[i][5];
                    double rk = (double) m.cycleStartSteadyStateState[i][6];
                    double cnt = (double) m.cycleStartSteadyStateState[i][7];
                    double productive = (double) m.cycleStartSteadyStateState[i][8];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double tp = m.cycleStartSteadyStateThroughput[i];

                    cCompleted[idx] = comp;
                    cBatchSize[idx] = bs;
                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cThroughput[idx] = tp;

                    if (cData.length > 0) {
                        cData[idx][0] = comp;
                        cData[idx][1] = bs;
                        cData[idx][2] = up;
                        cData[idx][3] = wrk;
                        cData[idx][4] = productive;
                        cData[idx][5] = productiveRatio;
                        cData[idx][6] = rk;
                        cData[idx][7] = cnt;
                        cData[idx][8] = tp;
                    }
                }
                cOffset += tLen;
            }
        }

        CycleStartScalars headScalars = new CycleStartScalars(
                ScalarSummary.of(hCompleted),
                ScalarSummary.of(hBatchSize),
                ScalarSummary.of(hUpstream),
                ScalarSummary.of(hWorkers),
                ScalarSummary.of(hProductive),
                ScalarSummary.of(hProductiveRatio),
                ScalarSummary.of(hRank),
                ScalarSummary.of(hContention),
                ScalarSummary.of(hThroughput));

        CycleStartScalars steadyStateScalars = new CycleStartScalars(
                ScalarSummary.of(tCompleted),
                ScalarSummary.of(tBatchSize),
                ScalarSummary.of(tUpstream),
                ScalarSummary.of(tWorkers),
                ScalarSummary.of(tProductive),
                ScalarSummary.of(tProductiveRatio),
                ScalarSummary.of(tRank),
                ScalarSummary.of(tContention),
                ScalarSummary.of(tThroughput));

        CycleStartScalars combinedScalars = new CycleStartScalars(
                ScalarSummary.of(cCompleted),
                ScalarSummary.of(cBatchSize),
                ScalarSummary.of(cUpstream),
                ScalarSummary.of(cWorkers),
                ScalarSummary.of(cProductive),
                ScalarSummary.of(cProductiveRatio),
                ScalarSummary.of(cRank),
                ScalarSummary.of(cContention),
                ScalarSummary.of(cThroughput));

        CorrelationResult hCorr = CorrelationResult.of(CycleStartStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(CycleStartStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(CycleStartStatistics.COLUMN_NAMES, cData);

        return new CycleStartStatistics(total, headScalars, steadyStateScalars, combinedScalars, hCorr, tCorr, cCorr);
    }

    private static BatchProgressStatistics calculateBatchProgress(List<HighSpeedMetrics> metricsList) {
        long total = 0L;
        int hTotalLen = 0;
        int tTotalLen = 0;
        int cTotalLen = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.batchProgressObservations;
            total += obs;
            if (obs > 0L) {
                int limit = m.rawSampleLimit;
                int hLen = (int) Math.min(obs, limit);
                int tLen = (int) Math.min(obs, limit);
                int cLen = obs <= hLen ? hLen : (hLen + tLen);
                hTotalLen += hLen;
                tTotalLen += tLen;
                cTotalLen += cLen;
            }
        }

        if (total == 0L) {
            return BatchProgressStatistics.EMPTY;
        }

        double[] hUpstream = new double[hTotalLen];
        double[] hWorkers = new double[hTotalLen];
        double[] hProductive = new double[hTotalLen];
        double[] hProductiveRatio = new double[hTotalLen];
        double[] hRank = new double[hTotalLen];
        double[] hContention = new double[hTotalLen];
        double[] hAvgService = new double[hTotalLen];
        double[][] hData = hTotalLen >= 2 ? new double[hTotalLen][3] : new double[0][0];

        double[] tUpstream = new double[tTotalLen];
        double[] tWorkers = new double[tTotalLen];
        double[] tProductive = new double[tTotalLen];
        double[] tProductiveRatio = new double[tTotalLen];
        double[] tRank = new double[tTotalLen];
        double[] tContention = new double[tTotalLen];
        double[] tAvgService = new double[tTotalLen];
        double[][] tData = tTotalLen >= 2 ? new double[tTotalLen][3] : new double[0][0];

        double[] cUpstream = new double[cTotalLen];
        double[] cWorkers = new double[cTotalLen];
        double[] cProductive = new double[cTotalLen];
        double[] cProductiveRatio = new double[cTotalLen];
        double[] cRank = new double[cTotalLen];
        double[] cContention = new double[cTotalLen];
        double[] cAvgService = new double[cTotalLen];
        double[][] cData = cTotalLen >= 2 ? new double[cTotalLen][3] : new double[0][0];

        int hOffset = 0;
        int tOffset = 0;
        int cOffset = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.batchProgressObservations;
            if (obs == 0L) {
                continue;
            }
            int limit = m.rawSampleLimit;
            int hLen = (int) Math.min(obs, limit);
            int tLen = (int) Math.min(obs, limit);

            // Head
            for (int i = 0; i < hLen; i++) {
                int idx = hOffset + i;
                double up = (double) m.batchProgressWarmupState[i][2];
                double wrk = (double) m.batchProgressWarmupState[i][3];
                double rk = (double) m.batchProgressWarmupState[i][4];
                double cnt = (double) m.batchProgressWarmupState[i][5];
                double productive = (double) m.batchProgressWarmupState[i][6];
                double productiveRatio = productiveHandleRatio(productive, wrk);
                double svc = m.batchProgressWarmupAvgServiceTime[i];

                hUpstream[idx] = up;
                hWorkers[idx] = wrk;
                hProductive[idx] = productive;
                hProductiveRatio[idx] = productiveRatio;
                hRank[idx] = rk;
                hContention[idx] = cnt;
                hAvgService[idx] = svc;

                if (hData.length > 0) {
                    hData[idx][0] = cnt;
                    hData[idx][1] = productiveRatio;
                    hData[idx][2] = svc;
                }
            }
            hOffset += hLen;

            // SteadyState
            for (int i = 0; i < tLen; i++) {
                int idx = tOffset + i;
                double up = (double) m.batchProgressSteadyStateState[i][2];
                double wrk = (double) m.batchProgressSteadyStateState[i][3];
                double rk = (double) m.batchProgressSteadyStateState[i][4];
                double cnt = (double) m.batchProgressSteadyStateState[i][5];
                double productive = (double) m.batchProgressSteadyStateState[i][6];
                double productiveRatio = productiveHandleRatio(productive, wrk);
                double svc = m.batchProgressSteadyStateAvgServiceTime[i];

                tUpstream[idx] = up;
                tWorkers[idx] = wrk;
                tProductive[idx] = productive;
                tProductiveRatio[idx] = productiveRatio;
                tRank[idx] = rk;
                tContention[idx] = cnt;
                tAvgService[idx] = svc;

                if (tData.length > 0) {
                    tData[idx][0] = cnt;
                    tData[idx][1] = productiveRatio;
                    tData[idx][2] = svc;
                }
            }
            tOffset += tLen;

            // Combined
            if (obs <= hLen) {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double up = (double) m.batchProgressWarmupState[i][2];
                    double wrk = (double) m.batchProgressWarmupState[i][3];
                    double rk = (double) m.batchProgressWarmupState[i][4];
                    double cnt = (double) m.batchProgressWarmupState[i][5];
                    double productive = (double) m.batchProgressWarmupState[i][6];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double svc = m.batchProgressWarmupAvgServiceTime[i];

                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cAvgService[idx] = svc;

                    if (cData.length > 0) {
                        cData[idx][0] = cnt;
                        cData[idx][1] = productiveRatio;
                        cData[idx][2] = svc;
                    }
                }
                cOffset += hLen;
            } else {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double up = (double) m.batchProgressWarmupState[i][2];
                    double wrk = (double) m.batchProgressWarmupState[i][3];
                    double rk = (double) m.batchProgressWarmupState[i][4];
                    double cnt = (double) m.batchProgressWarmupState[i][5];
                    double productive = (double) m.batchProgressWarmupState[i][6];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double svc = m.batchProgressWarmupAvgServiceTime[i];

                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cAvgService[idx] = svc;

                    if (cData.length > 0) {
                        cData[idx][0] = cnt;
                        cData[idx][1] = productiveRatio;
                        cData[idx][2] = svc;
                    }
                }
                cOffset += hLen;
                for (int i = 0; i < tLen; i++) {
                    int idx = cOffset + i;
                    double up = (double) m.batchProgressSteadyStateState[i][2];
                    double wrk = (double) m.batchProgressSteadyStateState[i][3];
                    double rk = (double) m.batchProgressSteadyStateState[i][4];
                    double cnt = (double) m.batchProgressSteadyStateState[i][5];
                    double productive = (double) m.batchProgressSteadyStateState[i][6];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double svc = m.batchProgressSteadyStateAvgServiceTime[i];

                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cAvgService[idx] = svc;

                    if (cData.length > 0) {
                        cData[idx][0] = cnt;
                        cData[idx][1] = productiveRatio;
                        cData[idx][2] = svc;
                    }
                }
                cOffset += tLen;
            }
        }

        BatchProgressScalars headScalars = new BatchProgressScalars(
                ScalarSummary.of(hUpstream),
                ScalarSummary.of(hWorkers),
                ScalarSummary.of(hProductive),
                ScalarSummary.of(hProductiveRatio),
                ScalarSummary.of(hRank),
                ScalarSummary.of(hContention),
                ScalarSummary.of(hAvgService));

        BatchProgressScalars steadyStateScalars = new BatchProgressScalars(
                ScalarSummary.of(tUpstream),
                ScalarSummary.of(tWorkers),
                ScalarSummary.of(tProductive),
                ScalarSummary.of(tProductiveRatio),
                ScalarSummary.of(tRank),
                ScalarSummary.of(tContention),
                ScalarSummary.of(tAvgService));

        BatchProgressScalars combinedScalars = new BatchProgressScalars(
                ScalarSummary.of(cUpstream),
                ScalarSummary.of(cWorkers),
                ScalarSummary.of(cProductive),
                ScalarSummary.of(cProductiveRatio),
                ScalarSummary.of(cRank),
                ScalarSummary.of(cContention),
                ScalarSummary.of(cAvgService));

        CorrelationResult hCorr = CorrelationResult.of(BatchProgressStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(BatchProgressStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(BatchProgressStatistics.COLUMN_NAMES, cData);

        return new BatchProgressStatistics(
                total, headScalars, steadyStateScalars, combinedScalars, hCorr, tCorr, cCorr);
    }

    private static BatchCompleteStatistics calculateBatchComplete(List<HighSpeedMetrics> metricsList) {
        long total = 0L;
        int hTotalLen = 0;
        int tTotalLen = 0;
        int cTotalLen = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.batchCompleteObservations;
            total += obs;
            if (obs > 0L) {
                int limit = m.rawSampleLimit;
                int hLen = (int) Math.min(obs, limit);
                int tLen = (int) Math.min(obs, limit);
                int cLen = obs <= hLen ? hLen : (hLen + tLen);
                hTotalLen += hLen;
                tTotalLen += tLen;
                cTotalLen += cLen;
            }
        }

        if (total == 0L) {
            return BatchCompleteStatistics.EMPTY;
        }

        double[] hUpstream = new double[hTotalLen];
        double[] hWorkers = new double[hTotalLen];
        double[] hProductive = new double[hTotalLen];
        double[] hProductiveRatio = new double[hTotalLen];
        double[] hRank = new double[hTotalLen];
        double[] hContention = new double[hTotalLen];
        double[] hAvgService = new double[hTotalLen];
        double[] hThroughput = new double[hTotalLen];
        double[][] hData = hTotalLen >= 2 ? new double[hTotalLen][4] : new double[0][0];

        double[] tUpstream = new double[tTotalLen];
        double[] tWorkers = new double[tTotalLen];
        double[] tProductive = new double[tTotalLen];
        double[] tProductiveRatio = new double[tTotalLen];
        double[] tRank = new double[tTotalLen];
        double[] tContention = new double[tTotalLen];
        double[] tAvgService = new double[tTotalLen];
        double[] tThroughput = new double[tTotalLen];
        double[][] tData = tTotalLen >= 2 ? new double[tTotalLen][4] : new double[0][0];

        double[] cUpstream = new double[cTotalLen];
        double[] cWorkers = new double[cTotalLen];
        double[] cProductive = new double[cTotalLen];
        double[] cProductiveRatio = new double[cTotalLen];
        double[] cRank = new double[cTotalLen];
        double[] cContention = new double[cTotalLen];
        double[] cAvgService = new double[cTotalLen];
        double[] cThroughput = new double[cTotalLen];
        double[][] cData = cTotalLen >= 2 ? new double[cTotalLen][4] : new double[0][0];

        int hOffset = 0;
        int tOffset = 0;
        int cOffset = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.batchCompleteObservations;
            if (obs == 0L) {
                continue;
            }
            int limit = m.rawSampleLimit;
            int hLen = (int) Math.min(obs, limit);
            int tLen = (int) Math.min(obs, limit);

            // Head
            for (int i = 0; i < hLen; i++) {
                int idx = hOffset + i;
                double up = (double) m.batchCompleteWarmupState[i][2];
                double wrk = (double) m.batchCompleteWarmupState[i][3];
                double rk = (double) m.batchCompleteWarmupState[i][4];
                double cnt = (double) m.batchCompleteWarmupState[i][5];
                double productive = (double) m.batchCompleteWarmupState[i][6];
                double productiveRatio = productiveHandleRatio(productive, wrk);
                double svc = m.batchCompleteWarmupAvgServiceTime[i];
                double tp = m.batchCompleteWarmupThroughput[i];

                hUpstream[idx] = up;
                hWorkers[idx] = wrk;
                hProductive[idx] = productive;
                hProductiveRatio[idx] = productiveRatio;
                hRank[idx] = rk;
                hContention[idx] = cnt;
                hAvgService[idx] = svc;
                hThroughput[idx] = tp;

                if (hData.length > 0) {
                    hData[idx][0] = cnt;
                    hData[idx][1] = productiveRatio;
                    hData[idx][2] = svc;
                    hData[idx][3] = tp;
                }
            }
            hOffset += hLen;

            // SteadyState
            for (int i = 0; i < tLen; i++) {
                int idx = tOffset + i;
                double up = (double) m.batchCompleteSteadyStateState[i][2];
                double wrk = (double) m.batchCompleteSteadyStateState[i][3];
                double rk = (double) m.batchCompleteSteadyStateState[i][4];
                double cnt = (double) m.batchCompleteSteadyStateState[i][5];
                double productive = (double) m.batchCompleteSteadyStateState[i][6];
                double productiveRatio = productiveHandleRatio(productive, wrk);
                double svc = m.batchCompleteSteadyStateAvgServiceTime[i];
                double tp = m.batchCompleteSteadyStateThroughput[i];

                tUpstream[idx] = up;
                tWorkers[idx] = wrk;
                tProductive[idx] = productive;
                tProductiveRatio[idx] = productiveRatio;
                tRank[idx] = rk;
                tContention[idx] = cnt;
                tAvgService[idx] = svc;
                tThroughput[idx] = tp;

                if (tData.length > 0) {
                    tData[idx][0] = cnt;
                    tData[idx][1] = productiveRatio;
                    tData[idx][2] = svc;
                    tData[idx][3] = tp;
                }
            }
            tOffset += tLen;

            // Combined
            if (obs <= hLen) {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double up = (double) m.batchCompleteWarmupState[i][2];
                    double wrk = (double) m.batchCompleteWarmupState[i][3];
                    double rk = (double) m.batchCompleteWarmupState[i][4];
                    double cnt = (double) m.batchCompleteWarmupState[i][5];
                    double productive = (double) m.batchCompleteWarmupState[i][6];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double svc = m.batchCompleteWarmupAvgServiceTime[i];
                    double tp = m.batchCompleteWarmupThroughput[i];

                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cAvgService[idx] = svc;
                    cThroughput[idx] = tp;

                    if (cData.length > 0) {
                        cData[idx][0] = cnt;
                        cData[idx][1] = productiveRatio;
                        cData[idx][2] = svc;
                        cData[idx][3] = tp;
                    }
                }
                cOffset += hLen;
            } else {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double up = (double) m.batchCompleteWarmupState[i][2];
                    double wrk = (double) m.batchCompleteWarmupState[i][3];
                    double rk = (double) m.batchCompleteWarmupState[i][4];
                    double cnt = (double) m.batchCompleteWarmupState[i][5];
                    double productive = (double) m.batchCompleteWarmupState[i][6];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double svc = m.batchCompleteWarmupAvgServiceTime[i];
                    double tp = m.batchCompleteWarmupThroughput[i];

                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cAvgService[idx] = svc;
                    cThroughput[idx] = tp;

                    if (cData.length > 0) {
                        cData[idx][0] = cnt;
                        cData[idx][1] = productiveRatio;
                        cData[idx][2] = svc;
                        cData[idx][3] = tp;
                    }
                }
                cOffset += hLen;
                for (int i = 0; i < tLen; i++) {
                    int idx = cOffset + i;
                    double up = (double) m.batchCompleteSteadyStateState[i][2];
                    double wrk = (double) m.batchCompleteSteadyStateState[i][3];
                    double rk = (double) m.batchCompleteSteadyStateState[i][4];
                    double cnt = (double) m.batchCompleteSteadyStateState[i][5];
                    double productive = (double) m.batchCompleteSteadyStateState[i][6];
                    double productiveRatio = productiveHandleRatio(productive, wrk);
                    double svc = m.batchCompleteSteadyStateAvgServiceTime[i];
                    double tp = m.batchCompleteSteadyStateThroughput[i];

                    cUpstream[idx] = up;
                    cWorkers[idx] = wrk;
                    cProductive[idx] = productive;
                    cProductiveRatio[idx] = productiveRatio;
                    cRank[idx] = rk;
                    cContention[idx] = cnt;
                    cAvgService[idx] = svc;
                    cThroughput[idx] = tp;

                    if (cData.length > 0) {
                        cData[idx][0] = cnt;
                        cData[idx][1] = productiveRatio;
                        cData[idx][2] = svc;
                        cData[idx][3] = tp;
                    }
                }
                cOffset += tLen;
            }
        }

        BatchCompleteScalars headScalars = new BatchCompleteScalars(
                ScalarSummary.of(hUpstream),
                ScalarSummary.of(hWorkers),
                ScalarSummary.of(hProductive),
                ScalarSummary.of(hProductiveRatio),
                ScalarSummary.of(hRank),
                ScalarSummary.of(hContention),
                ScalarSummary.of(hAvgService),
                ScalarSummary.of(hThroughput));

        BatchCompleteScalars steadyStateScalars = new BatchCompleteScalars(
                ScalarSummary.of(tUpstream),
                ScalarSummary.of(tWorkers),
                ScalarSummary.of(tProductive),
                ScalarSummary.of(tProductiveRatio),
                ScalarSummary.of(tRank),
                ScalarSummary.of(tContention),
                ScalarSummary.of(tAvgService),
                ScalarSummary.of(tThroughput));

        BatchCompleteScalars combinedScalars = new BatchCompleteScalars(
                ScalarSummary.of(cUpstream),
                ScalarSummary.of(cWorkers),
                ScalarSummary.of(cProductive),
                ScalarSummary.of(cProductiveRatio),
                ScalarSummary.of(cRank),
                ScalarSummary.of(cContention),
                ScalarSummary.of(cAvgService),
                ScalarSummary.of(cThroughput));

        CorrelationResult hCorr = CorrelationResult.of(BatchCompleteStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(BatchCompleteStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(BatchCompleteStatistics.COLUMN_NAMES, cData);

        return new BatchCompleteStatistics(
                total, headScalars, steadyStateScalars, combinedScalars, hCorr, tCorr, cCorr);
    }

    private static RawBodyCostStatistics calculateRawBodyCost(List<HighSpeedMetrics> metricsList) {
        long total = 0L;
        long totalCost = 0L;
        int hTotalLen = 0;
        int tTotalLen = 0;
        int cTotalLen = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.rawBodyCostObservations;
            total += obs;
            totalCost += m.rawBodyCostTotal;
            if (obs > 0L) {
                int limit = m.rawSampleLimit;
                int hLen = (int) Math.min(obs, limit);
                int tLen = (int) Math.min(obs, limit);
                int cLen = obs <= hLen ? hLen : (hLen + tLen);
                hTotalLen += hLen;
                tTotalLen += tLen;
                cTotalLen += cLen;
            }
        }

        if (total == 0L) {
            return RawBodyCostStatistics.EMPTY;
        }

        double[] hCost = new double[hTotalLen];
        double[] tCost = new double[tTotalLen];
        double[] cCost = new double[cTotalLen];

        int hOffset = 0;
        int tOffset = 0;
        int cOffset = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.rawBodyCostObservations;
            if (obs == 0L) {
                continue;
            }
            int limit = m.rawSampleLimit;
            int hLen = (int) Math.min(obs, limit);
            int tLen = (int) Math.min(obs, limit);

            for (int i = 0; i < hLen; i++) {
                hCost[hOffset + i] = (double) m.rawBodyCostWarmupState[i][2];
            }
            hOffset += hLen;

            for (int i = 0; i < tLen; i++) {
                tCost[tOffset + i] = (double) m.rawBodyCostSteadyStateState[i][2];
            }
            tOffset += tLen;

            if (obs <= hLen) {
                for (int i = 0; i < hLen; i++) {
                    cCost[cOffset + i] = (double) m.rawBodyCostWarmupState[i][2];
                }
                cOffset += hLen;
            } else {
                for (int i = 0; i < hLen; i++) {
                    cCost[cOffset + i] = (double) m.rawBodyCostWarmupState[i][2];
                }
                cOffset += hLen;
                for (int i = 0; i < tLen; i++) {
                    cCost[cOffset + i] = (double) m.rawBodyCostSteadyStateState[i][2];
                }
                cOffset += tLen;
            }
        }

        return new RawBodyCostStatistics(
                total, totalCost, ScalarSummary.of(hCost), ScalarSummary.of(tCost), ScalarSummary.of(cCost));
    }

    private static DecisionStatistics calculateIdleDecisions(List<HighSpeedMetrics> metricsList) {
        long total = 0L;
        int hTotalLen = 0;
        int tTotalLen = 0;
        int cTotalLen = 0;
        long[][] aggregatedBranchCounts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.idleDecisionObservations;
            total += obs;
            for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
                for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                    aggregatedBranchCounts[i][j] += m.idleBranchDecisionTotal[i][j];
                }
            }
            if (obs > 0L) {
                int limit = m.rawSampleLimit;
                int hLen = (int) Math.min(obs, limit);
                int tLen = (int) Math.min(obs, limit);
                int cLen = obs <= hLen ? hLen : (hLen + tLen);
                hTotalLen += hLen;
                tTotalLen += tLen;
                cTotalLen += cLen;
            }
        }

        BranchOccupancyResult occupancy = BranchOccupancyResult.of(aggregatedBranchCounts);
        if (total == 0L && occupancy.isEmpty()) {
            return DecisionStatistics.EMPTY;
        }
        if (total == 0L) {
            return new DecisionStatistics(
                    0L,
                    occupancy,
                    DecisionScalars.EMPTY,
                    DecisionScalars.EMPTY,
                    DecisionScalars.EMPTY,
                    TransitionAnalysis.compute(new int[0]),
                    TransitionAnalysis.compute(new int[0]),
                    VectorField.compute(new int[0]),
                    VectorField.compute(new int[0]),
                    CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                    CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                    CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES));
        }

        double[] hContention = new double[hTotalLen];
        double[] hSmoothed = new double[hTotalLen];
        double[][] hData = hTotalLen >= 2 ? new double[hTotalLen][3] : new double[0][0];

        double[] tContention = new double[tTotalLen];
        double[] tSmoothed = new double[tTotalLen];
        double[][] tData = tTotalLen >= 2 ? new double[tTotalLen][3] : new double[0][0];

        double[] cContention = new double[cTotalLen];
        double[] cSmoothed = new double[cTotalLen];
        double[][] cData = cTotalLen >= 2 ? new double[cTotalLen][3] : new double[0][0];

        long[][] headTransitionsCount = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        long[][] steadyStateTransitionsCount = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];

        int hOffset = 0;
        int tOffset = 0;
        int cOffset = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.idleDecisionObservations;
            if (obs == 0L) {
                continue;
            }
            int limit = m.rawSampleLimit;
            int hLen = (int) Math.min(obs, limit);
            int tLen = (int) Math.min(obs, limit);

            // Head
            for (int i = 0; i < hLen; i++) {
                int idx = hOffset + i;
                double cnt = (double) m.idleWarmupDecisionState[i][4];
                double cost = m.idleWarmupSmoothedBodyCost[i];

                hContention[idx] = cnt;
                hSmoothed[idx] = cost;

                if (hData.length > 0) {
                    hData[idx][0] = (double) m.idleWarmupDecisionState[i][2];
                    hData[idx][1] = (double) m.idleWarmupDecisionState[i][3];
                    hData[idx][2] = cost;
                }
            }
            int[] headStates = extractStateSequence(m.idleWarmupDecisionState, hLen);
            if (headStates.length >= 2) {
                for (int k = 0; k < headStates.length - 1; k++) {
                    headTransitionsCount[headStates[k]][headStates[k + 1]]++;
                }
            }
            hOffset += hLen;

            // SteadyState
            for (int i = 0; i < tLen; i++) {
                int idx = tOffset + i;
                double cnt = (double) m.idleSteadyStateDecisionState[i][4];
                double cost = m.idleSteadyStateSmoothedBodyCost[i];

                tContention[idx] = cnt;
                tSmoothed[idx] = cost;

                if (tData.length > 0) {
                    tData[idx][0] = (double) m.idleSteadyStateDecisionState[i][2];
                    tData[idx][1] = (double) m.idleSteadyStateDecisionState[i][3];
                    tData[idx][2] = cost;
                }
            }
            int[] steadyStateStates = extractStateSequence(m.idleSteadyStateDecisionState, tLen);
            if (steadyStateStates.length >= 2) {
                for (int k = 0; k < steadyStateStates.length - 1; k++) {
                    steadyStateTransitionsCount[steadyStateStates[k]][steadyStateStates[k + 1]]++;
                }
            }
            tOffset += tLen;

            // Combined
            if (obs <= hLen) {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double cnt = (double) m.idleWarmupDecisionState[i][4];
                    double cost = m.idleWarmupSmoothedBodyCost[i];

                    cContention[idx] = cnt;
                    cSmoothed[idx] = cost;

                    if (cData.length > 0) {
                        cData[idx][0] = (double) m.idleWarmupDecisionState[i][2];
                        cData[idx][1] = (double) m.idleWarmupDecisionState[i][3];
                        cData[idx][2] = cost;
                    }
                }
                cOffset += hLen;
            } else {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double cnt = (double) m.idleWarmupDecisionState[i][4];
                    double cost = m.idleWarmupSmoothedBodyCost[i];

                    cContention[idx] = cnt;
                    cSmoothed[idx] = cost;

                    if (cData.length > 0) {
                        cData[idx][0] = (double) m.idleWarmupDecisionState[i][2];
                        cData[idx][1] = (double) m.idleWarmupDecisionState[i][3];
                        cData[idx][2] = cost;
                    }
                }
                cOffset += hLen;
                for (int i = 0; i < tLen; i++) {
                    int idx = cOffset + i;
                    double cnt = (double) m.idleSteadyStateDecisionState[i][4];
                    double cost = m.idleSteadyStateSmoothedBodyCost[i];

                    cContention[idx] = cnt;
                    cSmoothed[idx] = cost;

                    if (cData.length > 0) {
                        cData[idx][0] = (double) m.idleSteadyStateDecisionState[i][2];
                        cData[idx][1] = (double) m.idleSteadyStateDecisionState[i][3];
                        cData[idx][2] = cost;
                    }
                }
                cOffset += tLen;
            }
        }

        DecisionScalars headScalars = new DecisionScalars(ScalarSummary.of(hContention), ScalarSummary.of(hSmoothed));
        DecisionScalars steadyStateScalars =
                new DecisionScalars(ScalarSummary.of(tContention), ScalarSummary.of(tSmoothed));
        DecisionScalars combinedScalars =
                new DecisionScalars(ScalarSummary.of(cContention), ScalarSummary.of(cSmoothed));

        TransitionAnalysis headTransitions = TransitionAnalysis.computeFromCounts(headTransitionsCount);
        TransitionAnalysis steadyStateTransitions = TransitionAnalysis.computeFromCounts(steadyStateTransitionsCount);

        VectorField headVectorField = VectorField.compute(headTransitions.transitionCounts());
        VectorField steadyStateVectorField = VectorField.compute(steadyStateTransitions.transitionCounts());

        CorrelationResult hCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, cData);

        return new DecisionStatistics(
                total,
                occupancy,
                headScalars,
                steadyStateScalars,
                combinedScalars,
                headTransitions,
                steadyStateTransitions,
                headVectorField,
                steadyStateVectorField,
                hCorr,
                tCorr,
                cCorr);
    }

    private static DecisionStatistics calculateExecDecisions(List<HighSpeedMetrics> metricsList) {
        long total = 0L;
        int hTotalLen = 0;
        int tTotalLen = 0;
        int cTotalLen = 0;
        long[][] aggregatedBranchCounts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.execDecisionObservations;
            total += obs;
            for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
                for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                    aggregatedBranchCounts[i][j] += m.execBranchDecisionTotal[i][j];
                }
            }
            if (obs > 0L) {
                int limit = m.rawSampleLimit;
                int hLen = (int) Math.min(obs, limit);
                int tLen = (int) Math.min(obs, limit);
                int cLen = obs <= hLen ? hLen : (hLen + tLen);
                hTotalLen += hLen;
                tTotalLen += tLen;
                cTotalLen += cLen;
            }
        }

        BranchOccupancyResult occupancy = BranchOccupancyResult.of(aggregatedBranchCounts);
        if (total == 0L && occupancy.isEmpty()) {
            return DecisionStatistics.EMPTY;
        }
        if (total == 0L) {
            return new DecisionStatistics(
                    0L,
                    occupancy,
                    DecisionScalars.EMPTY,
                    DecisionScalars.EMPTY,
                    DecisionScalars.EMPTY,
                    TransitionAnalysis.compute(new int[0]),
                    TransitionAnalysis.compute(new int[0]),
                    VectorField.compute(new int[0]),
                    VectorField.compute(new int[0]),
                    CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                    CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                    CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES));
        }

        double[] hContention = new double[hTotalLen];
        double[] hSmoothed = new double[hTotalLen];
        double[][] hData = hTotalLen >= 2 ? new double[hTotalLen][3] : new double[0][0];

        double[] tContention = new double[tTotalLen];
        double[] tSmoothed = new double[tTotalLen];
        double[][] tData = tTotalLen >= 2 ? new double[tTotalLen][3] : new double[0][0];

        double[] cContention = new double[cTotalLen];
        double[] cSmoothed = new double[cTotalLen];
        double[][] cData = cTotalLen >= 2 ? new double[cTotalLen][3] : new double[0][0];

        long[][] headTransitionsCount = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        long[][] steadyStateTransitionsCount = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];

        int hOffset = 0;
        int tOffset = 0;
        int cOffset = 0;

        for (HighSpeedMetrics m : metricsList) {
            long obs = m.execDecisionObservations;
            if (obs == 0L) {
                continue;
            }
            int limit = m.rawSampleLimit;
            int hLen = (int) Math.min(obs, limit);
            int tLen = (int) Math.min(obs, limit);

            // Head
            for (int i = 0; i < hLen; i++) {
                int idx = hOffset + i;
                double cnt = (double) m.execWarmupDecisionState[i][4];
                double cost = m.execWarmupSmoothedBodyCost[i];

                hContention[idx] = cnt;
                hSmoothed[idx] = cost;

                if (hData.length > 0) {
                    hData[idx][0] = (double) m.execWarmupDecisionState[i][2];
                    hData[idx][1] = (double) m.execWarmupDecisionState[i][3];
                    hData[idx][2] = cost;
                }
            }
            int[] headStates = extractStateSequence(m.execWarmupDecisionState, hLen);
            if (headStates.length >= 2) {
                for (int k = 0; k < headStates.length - 1; k++) {
                    headTransitionsCount[headStates[k]][headStates[k + 1]]++;
                }
            }
            hOffset += hLen;

            // SteadyState
            for (int i = 0; i < tLen; i++) {
                int idx = tOffset + i;
                double cnt = (double) m.execSteadyStateDecisionState[i][4];
                double cost = m.execSteadyStateSmoothedBodyCost[i];

                tContention[idx] = cnt;
                tSmoothed[idx] = cost;

                if (tData.length > 0) {
                    tData[idx][0] = (double) m.execSteadyStateDecisionState[i][2];
                    tData[idx][1] = (double) m.execSteadyStateDecisionState[i][3];
                    tData[idx][2] = cost;
                }
            }
            int[] steadyStateStates = extractStateSequence(m.execSteadyStateDecisionState, tLen);
            if (steadyStateStates.length >= 2) {
                for (int k = 0; k < steadyStateStates.length - 1; k++) {
                    steadyStateTransitionsCount[steadyStateStates[k]][steadyStateStates[k + 1]]++;
                }
            }
            tOffset += tLen;

            // Combined
            if (obs <= hLen) {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double cnt = (double) m.execWarmupDecisionState[i][4];
                    double cost = m.execWarmupSmoothedBodyCost[i];

                    cContention[idx] = cnt;
                    cSmoothed[idx] = cost;

                    if (cData.length > 0) {
                        cData[idx][0] = (double) m.execWarmupDecisionState[i][2];
                        cData[idx][1] = (double) m.execWarmupDecisionState[i][3];
                        cData[idx][2] = cost;
                    }
                }
                cOffset += hLen;
            } else {
                for (int i = 0; i < hLen; i++) {
                    int idx = cOffset + i;
                    double cnt = (double) m.execWarmupDecisionState[i][4];
                    double cost = m.execWarmupSmoothedBodyCost[i];

                    cContention[idx] = cnt;
                    cSmoothed[idx] = cost;

                    if (cData.length > 0) {
                        cData[idx][0] = (double) m.execWarmupDecisionState[i][2];
                        cData[idx][1] = (double) m.execWarmupDecisionState[i][3];
                        cData[idx][2] = cost;
                    }
                }
                cOffset += hLen;
                for (int i = 0; i < tLen; i++) {
                    int idx = cOffset + i;
                    double cnt = (double) m.execSteadyStateDecisionState[i][4];
                    double cost = m.execSteadyStateSmoothedBodyCost[i];

                    cContention[idx] = cnt;
                    cSmoothed[idx] = cost;

                    if (cData.length > 0) {
                        cData[idx][0] = (double) m.execSteadyStateDecisionState[i][2];
                        cData[idx][1] = (double) m.execSteadyStateDecisionState[i][3];
                        cData[idx][2] = cost;
                    }
                }
                cOffset += tLen;
            }
        }

        DecisionScalars headScalars = new DecisionScalars(ScalarSummary.of(hContention), ScalarSummary.of(hSmoothed));
        DecisionScalars steadyStateScalars =
                new DecisionScalars(ScalarSummary.of(tContention), ScalarSummary.of(tSmoothed));
        DecisionScalars combinedScalars =
                new DecisionScalars(ScalarSummary.of(cContention), ScalarSummary.of(cSmoothed));

        TransitionAnalysis headTransitions = TransitionAnalysis.computeFromCounts(headTransitionsCount);
        TransitionAnalysis steadyStateTransitions = TransitionAnalysis.computeFromCounts(steadyStateTransitionsCount);

        VectorField headVectorField = VectorField.compute(headTransitions.transitionCounts());
        VectorField steadyStateVectorField = VectorField.compute(steadyStateTransitions.transitionCounts());

        CorrelationResult hCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, cData);

        return new DecisionStatistics(
                total,
                occupancy,
                headScalars,
                steadyStateScalars,
                combinedScalars,
                headTransitions,
                steadyStateTransitions,
                headVectorField,
                steadyStateVectorField,
                hCorr,
                tCorr,
                cCorr);
    }

    private static int[] extractStateSequence(long[][] decisionState, int len) {
        if (len == 0 || decisionState == null) {
            return new int[0];
        }
        int[] states = new int[len];
        for (int i = 0; i < len; i++) {
            int contentionPolicy = (int) decisionState[i][2];
            int bodyPolicy = (int) decisionState[i][3];
            states[i] = TransitionAnalysis.toState(contentionPolicy, bodyPolicy);
        }
        return states;
    }
}
