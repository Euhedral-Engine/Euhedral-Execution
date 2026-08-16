package calibration.statistics;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
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
import calibration.statistics.iteration.TransitionAnalysis;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Authoritative calculation engine for detached HighSpeedMetrics.
///
/// Converts captured raw observation buffers and exact branch counters into immutable
/// descriptive, quantile, occupancy, transition, vector-field, and correlation results.
public final class HighSpeedMetricsStatistics {

    private HighSpeedMetricsStatistics() {}

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
        int limit = metrics.rawSampleLimit;

        CycleStartStatistics cycleStart = calculateCycleStart(metrics, limit);
        BatchProgressStatistics batchProgress = calculateBatchProgress(metrics, limit);
        BatchCompleteStatistics batchComplete = calculateBatchComplete(metrics, limit);
        RawBodyCostStatistics rawBodyCost = calculateRawBodyCost(metrics, limit);
        DecisionStatistics idleDecisions = calculateIdleDecisions(metrics, limit);
        DecisionStatistics execDecisions = calculateExecDecisions(metrics, limit);

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

    private static CycleStartStatistics calculateCycleStart(HighSpeedMetrics metrics, int limit) {
        long total = metrics.cycleStartObservations;
        if (total == 0L) {
            return CycleStartStatistics.EMPTY;
        }

        int hLen = (int) Math.min(total, limit);
        int tLen = (int) Math.min(total, limit);

        double[] hCompleted = extractColumn(metrics.cycleStartWarmupState, hLen, 2);
        double[] tCompleted = extractColumn(metrics.cycleStartSteadyStateState, tLen, 2);
        double[] cCompleted =
                combineColumns(metrics.cycleStartWarmupState, hLen, metrics.cycleStartSteadyStateState, tLen, total, 2);

        double[] hBatchSize = extractColumn(metrics.cycleStartWarmupState, hLen, 3);
        double[] tBatchSize = extractColumn(metrics.cycleStartSteadyStateState, tLen, 3);
        double[] cBatchSize =
                combineColumns(metrics.cycleStartWarmupState, hLen, metrics.cycleStartSteadyStateState, tLen, total, 3);

        double[] hUpstream = extractColumn(metrics.cycleStartWarmupState, hLen, 4);
        double[] tUpstream = extractColumn(metrics.cycleStartSteadyStateState, tLen, 4);
        double[] cUpstream =
                combineColumns(metrics.cycleStartWarmupState, hLen, metrics.cycleStartSteadyStateState, tLen, total, 4);

        double[] hWorkers = extractColumn(metrics.cycleStartWarmupState, hLen, 5);
        double[] tWorkers = extractColumn(metrics.cycleStartSteadyStateState, tLen, 5);
        double[] cWorkers =
                combineColumns(metrics.cycleStartWarmupState, hLen, metrics.cycleStartSteadyStateState, tLen, total, 5);

        double[] hRank = extractColumn(metrics.cycleStartWarmupState, hLen, 6);
        double[] tRank = extractColumn(metrics.cycleStartSteadyStateState, tLen, 6);
        double[] cRank =
                combineColumns(metrics.cycleStartWarmupState, hLen, metrics.cycleStartSteadyStateState, tLen, total, 6);

        double[] hContention = extractColumn(metrics.cycleStartWarmupState, hLen, 7);
        double[] tContention = extractColumn(metrics.cycleStartSteadyStateState, tLen, 7);
        double[] cContention =
                combineColumns(metrics.cycleStartWarmupState, hLen, metrics.cycleStartSteadyStateState, tLen, total, 7);

        double[] hThroughput = extractDouble(metrics.cycleStartWarmupThroughput, hLen);
        double[] tThroughput = extractDouble(metrics.cycleStartSteadyStateThroughput, tLen);
        double[] cThroughput = combineDoubles(
                metrics.cycleStartWarmupThroughput, hLen, metrics.cycleStartSteadyStateThroughput, tLen, total);

        CycleStartScalars headScalars = new CycleStartScalars(
                ScalarSummary.of(hCompleted),
                ScalarSummary.of(hBatchSize),
                ScalarSummary.of(hUpstream),
                ScalarSummary.of(hWorkers),
                ScalarSummary.of(hRank),
                ScalarSummary.of(hContention),
                ScalarSummary.of(hThroughput));

        CycleStartScalars SteadyStateScalars = new CycleStartScalars(
                ScalarSummary.of(tCompleted),
                ScalarSummary.of(tBatchSize),
                ScalarSummary.of(tUpstream),
                ScalarSummary.of(tWorkers),
                ScalarSummary.of(tRank),
                ScalarSummary.of(tContention),
                ScalarSummary.of(tThroughput));

        CycleStartScalars combinedScalars = new CycleStartScalars(
                ScalarSummary.of(cCompleted),
                ScalarSummary.of(cBatchSize),
                ScalarSummary.of(cUpstream),
                ScalarSummary.of(cWorkers),
                ScalarSummary.of(cRank),
                ScalarSummary.of(cContention),
                ScalarSummary.of(cThroughput));

        double[][] hData =
                buildCycleStartMatrix(metrics.cycleStartWarmupState, metrics.cycleStartWarmupThroughput, hLen);
        double[][] tData = buildCycleStartMatrix(
                metrics.cycleStartSteadyStateState, metrics.cycleStartSteadyStateThroughput, tLen);
        double[][] cData = buildCycleStartCombinedMatrix(
                metrics.cycleStartWarmupState,
                metrics.cycleStartWarmupThroughput,
                hLen,
                metrics.cycleStartSteadyStateState,
                metrics.cycleStartSteadyStateThroughput,
                tLen,
                total);

        CorrelationResult hCorr = CorrelationResult.of(CycleStartStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(CycleStartStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(CycleStartStatistics.COLUMN_NAMES, cData);

        return new CycleStartStatistics(total, headScalars, SteadyStateScalars, combinedScalars, hCorr, tCorr, cCorr);
    }

    private static BatchProgressStatistics calculateBatchProgress(HighSpeedMetrics metrics, int limit) {
        long total = metrics.batchProgressObservations;
        if (total == 0L) {
            return BatchProgressStatistics.EMPTY;
        }

        int hLen = (int) Math.min(total, limit);
        int tLen = (int) Math.min(total, limit);

        double[] hUpstream = extractColumn(metrics.batchProgressWarmupState, hLen, 2);
        double[] tUpstream = extractColumn(metrics.batchProgressSteadyStateState, tLen, 2);
        double[] cUpstream = combineColumns(
                metrics.batchProgressWarmupState, hLen, metrics.batchProgressSteadyStateState, tLen, total, 2);

        double[] hWorkers = extractColumn(metrics.batchProgressWarmupState, hLen, 3);
        double[] tWorkers = extractColumn(metrics.batchProgressSteadyStateState, tLen, 3);
        double[] cWorkers = combineColumns(
                metrics.batchProgressWarmupState, hLen, metrics.batchProgressSteadyStateState, tLen, total, 3);

        double[] hRank = extractColumn(metrics.batchProgressWarmupState, hLen, 4);
        double[] tRank = extractColumn(metrics.batchProgressSteadyStateState, tLen, 4);
        double[] cRank = combineColumns(
                metrics.batchProgressWarmupState, hLen, metrics.batchProgressSteadyStateState, tLen, total, 4);

        double[] hContention = extractColumn(metrics.batchProgressWarmupState, hLen, 5);
        double[] tContention = extractColumn(metrics.batchProgressSteadyStateState, tLen, 5);
        double[] cContention = combineColumns(
                metrics.batchProgressWarmupState, hLen, metrics.batchProgressSteadyStateState, tLen, total, 5);

        double[] hAvgService = extractDouble(metrics.batchProgressWarmupAvgServiceTime, hLen);
        double[] tAvgService = extractDouble(metrics.batchProgressSteadyStateAvgServiceTime, tLen);
        double[] cAvgService = combineDoubles(
                metrics.batchProgressWarmupAvgServiceTime,
                hLen,
                metrics.batchProgressSteadyStateAvgServiceTime,
                tLen,
                total);

        BatchProgressScalars headScalars = new BatchProgressScalars(
                ScalarSummary.of(hUpstream),
                ScalarSummary.of(hWorkers),
                ScalarSummary.of(hRank),
                ScalarSummary.of(hContention),
                ScalarSummary.of(hAvgService));

        BatchProgressScalars SteadyStateScalars = new BatchProgressScalars(
                ScalarSummary.of(tUpstream),
                ScalarSummary.of(tWorkers),
                ScalarSummary.of(tRank),
                ScalarSummary.of(tContention),
                ScalarSummary.of(tAvgService));

        BatchProgressScalars combinedScalars = new BatchProgressScalars(
                ScalarSummary.of(cUpstream),
                ScalarSummary.of(cWorkers),
                ScalarSummary.of(cRank),
                ScalarSummary.of(cContention),
                ScalarSummary.of(cAvgService));

        double[][] hData = buildBatchProgressMatrix(
                metrics.batchProgressWarmupState, metrics.batchProgressWarmupAvgServiceTime, hLen);
        double[][] tData = buildBatchProgressMatrix(
                metrics.batchProgressSteadyStateState, metrics.batchProgressSteadyStateAvgServiceTime, tLen);
        double[][] cData = buildBatchProgressCombinedMatrix(
                metrics.batchProgressWarmupState,
                metrics.batchProgressWarmupAvgServiceTime,
                hLen,
                metrics.batchProgressSteadyStateState,
                metrics.batchProgressSteadyStateAvgServiceTime,
                tLen,
                total);

        CorrelationResult hCorr = CorrelationResult.of(BatchProgressStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(BatchProgressStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(BatchProgressStatistics.COLUMN_NAMES, cData);

        return new BatchProgressStatistics(
                total, headScalars, SteadyStateScalars, combinedScalars, hCorr, tCorr, cCorr);
    }

    private static BatchCompleteStatistics calculateBatchComplete(HighSpeedMetrics metrics, int limit) {
        long total = metrics.batchCompleteObservations;
        if (total == 0L) {
            return BatchCompleteStatistics.EMPTY;
        }

        int hLen = (int) Math.min(total, limit);
        int tLen = (int) Math.min(total, limit);

        double[] hUpstream = extractColumn(metrics.batchCompleteWarmupState, hLen, 2);
        double[] tUpstream = extractColumn(metrics.batchCompleteSteadyStateState, tLen, 2);
        double[] cUpstream = combineColumns(
                metrics.batchCompleteWarmupState, hLen, metrics.batchCompleteSteadyStateState, tLen, total, 2);

        double[] hWorkers = extractColumn(metrics.batchCompleteWarmupState, hLen, 3);
        double[] tWorkers = extractColumn(metrics.batchCompleteSteadyStateState, tLen, 3);
        double[] cWorkers = combineColumns(
                metrics.batchCompleteWarmupState, hLen, metrics.batchCompleteSteadyStateState, tLen, total, 3);

        double[] hRank = extractColumn(metrics.batchCompleteWarmupState, hLen, 4);
        double[] tRank = extractColumn(metrics.batchCompleteSteadyStateState, tLen, 4);
        double[] cRank = combineColumns(
                metrics.batchCompleteWarmupState, hLen, metrics.batchCompleteSteadyStateState, tLen, total, 4);

        double[] hContention = extractColumn(metrics.batchCompleteWarmupState, hLen, 5);
        double[] tContention = extractColumn(metrics.batchCompleteSteadyStateState, tLen, 5);
        double[] cContention = combineColumns(
                metrics.batchCompleteWarmupState, hLen, metrics.batchCompleteSteadyStateState, tLen, total, 5);

        double[] hAvgService = extractDouble(metrics.batchCompleteWarmupAvgServiceTime, hLen);
        double[] tAvgService = extractDouble(metrics.batchCompleteSteadyStateAvgServiceTime, tLen);
        double[] cAvgService = combineDoubles(
                metrics.batchCompleteWarmupAvgServiceTime,
                hLen,
                metrics.batchCompleteSteadyStateAvgServiceTime,
                tLen,
                total);

        double[] hThroughput = extractDouble(metrics.batchCompleteWarmupThroughput, hLen);
        double[] tThroughput = extractDouble(metrics.batchCompleteSteadyStateThroughput, tLen);
        double[] cThroughput = combineDoubles(
                metrics.batchCompleteWarmupThroughput, hLen, metrics.batchCompleteSteadyStateThroughput, tLen, total);

        BatchCompleteScalars headScalars = new BatchCompleteScalars(
                ScalarSummary.of(hUpstream),
                ScalarSummary.of(hWorkers),
                ScalarSummary.of(hRank),
                ScalarSummary.of(hContention),
                ScalarSummary.of(hAvgService),
                ScalarSummary.of(hThroughput));

        BatchCompleteScalars SteadyStateScalars = new BatchCompleteScalars(
                ScalarSummary.of(tUpstream),
                ScalarSummary.of(tWorkers),
                ScalarSummary.of(tRank),
                ScalarSummary.of(tContention),
                ScalarSummary.of(tAvgService),
                ScalarSummary.of(tThroughput));

        BatchCompleteScalars combinedScalars = new BatchCompleteScalars(
                ScalarSummary.of(cUpstream),
                ScalarSummary.of(cWorkers),
                ScalarSummary.of(cRank),
                ScalarSummary.of(cContention),
                ScalarSummary.of(cAvgService),
                ScalarSummary.of(cThroughput));

        double[][] hData = buildBatchCompleteMatrix(
                metrics.batchCompleteWarmupState,
                metrics.batchCompleteWarmupAvgServiceTime,
                metrics.batchCompleteWarmupThroughput,
                hLen);
        double[][] tData = buildBatchCompleteMatrix(
                metrics.batchCompleteSteadyStateState,
                metrics.batchCompleteSteadyStateAvgServiceTime,
                metrics.batchCompleteSteadyStateThroughput,
                tLen);
        double[][] cData = buildBatchCompleteCombinedMatrix(
                metrics.batchCompleteWarmupState,
                metrics.batchCompleteWarmupAvgServiceTime,
                metrics.batchCompleteWarmupThroughput,
                hLen,
                metrics.batchCompleteSteadyStateState,
                metrics.batchCompleteSteadyStateAvgServiceTime,
                metrics.batchCompleteSteadyStateThroughput,
                tLen,
                total);

        CorrelationResult hCorr = CorrelationResult.of(BatchCompleteStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(BatchCompleteStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(BatchCompleteStatistics.COLUMN_NAMES, cData);

        return new BatchCompleteStatistics(
                total, headScalars, SteadyStateScalars, combinedScalars, hCorr, tCorr, cCorr);
    }

    private static RawBodyCostStatistics calculateRawBodyCost(HighSpeedMetrics metrics, int limit) {
        long total = metrics.rawBodyCostObservations;
        long totalCost = metrics.rawBodyCostTotal;
        if (total == 0L) {
            return RawBodyCostStatistics.EMPTY;
        }

        int hLen = (int) Math.min(total, limit);
        int tLen = (int) Math.min(total, limit);

        double[] hCost = extractColumn(metrics.rawBodyCostWarmupState, hLen, 2);
        double[] tCost = extractColumn(metrics.rawBodyCostSteadyStateState, tLen, 2);
        double[] cCost = combineColumns(
                metrics.rawBodyCostWarmupState, hLen, metrics.rawBodyCostSteadyStateState, tLen, total, 2);

        return new RawBodyCostStatistics(
                total, totalCost, ScalarSummary.of(hCost), ScalarSummary.of(tCost), ScalarSummary.of(cCost));
    }

    private static DecisionStatistics calculateIdleDecisions(HighSpeedMetrics metrics, int limit) {
        long total = metrics.idleDecisionObservations;
        BranchOccupancyResult occupancy = BranchOccupancyResult.of(metrics.idleBranchDecisionTotal);
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

        int hLen = (int) Math.min(total, limit);
        int tLen = (int) Math.min(total, limit);

        double[] hContention = extractColumn(metrics.idleWarmupDecisionState, hLen, 4);
        double[] tContention = extractColumn(metrics.idleSteadyStateDecisionState, tLen, 4);
        double[] cContention = combineColumns(
                metrics.idleWarmupDecisionState, hLen, metrics.idleSteadyStateDecisionState, tLen, total, 4);

        double[] hSmoothed = extractDouble(metrics.idleWarmupSmoothedBodyCost, hLen);
        double[] tSmoothed = extractDouble(metrics.idleSteadyStateSmoothedBodyCost, tLen);
        double[] cSmoothed = combineDoubles(
                metrics.idleWarmupSmoothedBodyCost, hLen, metrics.idleSteadyStateSmoothedBodyCost, tLen, total);

        DecisionScalars headScalars = new DecisionScalars(ScalarSummary.of(hContention), ScalarSummary.of(hSmoothed));
        DecisionScalars SteadyStateScalars =
                new DecisionScalars(ScalarSummary.of(tContention), ScalarSummary.of(tSmoothed));
        DecisionScalars combinedScalars =
                new DecisionScalars(ScalarSummary.of(cContention), ScalarSummary.of(cSmoothed));

        int[] headStates = extractStateSequence(metrics.idleWarmupDecisionState, hLen);
        int[] SteadyStateStates = extractStateSequence(metrics.idleSteadyStateDecisionState, tLen);

        TransitionAnalysis headTransitions = TransitionAnalysis.compute(headStates);
        TransitionAnalysis SteadyStateTransitions = TransitionAnalysis.compute(SteadyStateStates);

        VectorField headVectorField = VectorField.compute(headTransitions.transitionCounts());
        VectorField SteadyStateVectorField = VectorField.compute(SteadyStateTransitions.transitionCounts());

        double[][] hData =
                buildDecisionMatrix(metrics.idleWarmupDecisionState, metrics.idleWarmupSmoothedBodyCost, hLen);
        double[][] tData = buildDecisionMatrix(
                metrics.idleSteadyStateDecisionState, metrics.idleSteadyStateSmoothedBodyCost, tLen);
        double[][] cData = buildDecisionCombinedMatrix(
                metrics.idleWarmupDecisionState,
                metrics.idleWarmupSmoothedBodyCost,
                hLen,
                metrics.idleSteadyStateDecisionState,
                metrics.idleSteadyStateSmoothedBodyCost,
                tLen,
                total);

        CorrelationResult hCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, cData);

        return new DecisionStatistics(
                total,
                occupancy,
                headScalars,
                SteadyStateScalars,
                combinedScalars,
                headTransitions,
                SteadyStateTransitions,
                headVectorField,
                SteadyStateVectorField,
                hCorr,
                tCorr,
                cCorr);
    }

    private static DecisionStatistics calculateExecDecisions(HighSpeedMetrics metrics, int limit) {
        long total = metrics.execDecisionObservations;
        BranchOccupancyResult occupancy = BranchOccupancyResult.of(metrics.execBranchDecisionTotal);
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

        int hLen = (int) Math.min(total, limit);
        int tLen = (int) Math.min(total, limit);

        double[] hContention = extractColumn(metrics.execWarmupDecisionState, hLen, 4);
        double[] tContention = extractColumn(metrics.execSteadyStateDecisionState, tLen, 4);
        double[] cContention = combineColumns(
                metrics.execWarmupDecisionState, hLen, metrics.execSteadyStateDecisionState, tLen, total, 4);

        double[] hSmoothed = extractDouble(metrics.execWarmupSmoothedBodyCost, hLen);
        double[] tSmoothed = extractDouble(metrics.execSteadyStateSmoothedBodyCost, tLen);
        double[] cSmoothed = combineDoubles(
                metrics.execWarmupSmoothedBodyCost, hLen, metrics.execSteadyStateSmoothedBodyCost, tLen, total);

        DecisionScalars headScalars = new DecisionScalars(ScalarSummary.of(hContention), ScalarSummary.of(hSmoothed));
        DecisionScalars SteadyStateScalars =
                new DecisionScalars(ScalarSummary.of(tContention), ScalarSummary.of(tSmoothed));
        DecisionScalars combinedScalars =
                new DecisionScalars(ScalarSummary.of(cContention), ScalarSummary.of(cSmoothed));

        int[] headStates = extractStateSequence(metrics.execWarmupDecisionState, hLen);
        int[] SteadyStateStates = extractStateSequence(metrics.execSteadyStateDecisionState, tLen);

        TransitionAnalysis headTransitions = TransitionAnalysis.compute(headStates);
        TransitionAnalysis SteadyStateTransitions = TransitionAnalysis.compute(SteadyStateStates);

        VectorField headVectorField = VectorField.compute(headTransitions.transitionCounts());
        VectorField SteadyStateVectorField = VectorField.compute(SteadyStateTransitions.transitionCounts());

        double[][] hData =
                buildDecisionMatrix(metrics.execWarmupDecisionState, metrics.execWarmupSmoothedBodyCost, hLen);
        double[][] tData = buildDecisionMatrix(
                metrics.execSteadyStateDecisionState, metrics.execSteadyStateSmoothedBodyCost, tLen);
        double[][] cData = buildDecisionCombinedMatrix(
                metrics.execWarmupDecisionState,
                metrics.execWarmupSmoothedBodyCost,
                hLen,
                metrics.execSteadyStateDecisionState,
                metrics.execSteadyStateSmoothedBodyCost,
                tLen,
                total);

        CorrelationResult hCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, hData);
        CorrelationResult tCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, tData);
        CorrelationResult cCorr = CorrelationResult.of(DecisionStatistics.COLUMN_NAMES, cData);

        return new DecisionStatistics(
                total,
                occupancy,
                headScalars,
                SteadyStateScalars,
                combinedScalars,
                headTransitions,
                SteadyStateTransitions,
                headVectorField,
                SteadyStateVectorField,
                hCorr,
                tCorr,
                cCorr);
    }

    private static double[] extractColumn(long[][] state, int len, int col) {
        if (len == 0 || state == null) {
            return new double[0];
        }
        double[] res = new double[len];
        for (int i = 0; i < len; i++) {
            res[i] = (double) state[i][col];
        }
        return res;
    }

    private static double[] extractDouble(double[] arr, int len) {
        if (len == 0 || arr == null) {
            return new double[0];
        }
        double[] res = new double[len];
        System.arraycopy(arr, 0, res, 0, len);
        return res;
    }

    private static double[] combineColumns(
            long[][] warmup, int hLen, long[][] SteadyState, int tLen, long totalObs, int col) {
        if (totalObs == 0L) {
            return new double[0];
        }
        if (totalObs <= hLen) {
            return extractColumn(warmup, hLen, col);
        }
        double[] res = new double[hLen + tLen];
        for (int i = 0; i < hLen; i++) {
            res[i] = (double) warmup[i][col];
        }
        for (int i = 0; i < tLen; i++) {
            res[hLen + i] = (double) SteadyState[i][col];
        }
        return res;
    }

    private static double[] combineDoubles(double[] warmup, int hLen, double[] SteadyState, int tLen, long totalObs) {
        if (totalObs == 0L) {
            return new double[0];
        }
        if (totalObs <= hLen) {
            return extractDouble(warmup, hLen);
        }
        double[] res = new double[hLen + tLen];
        System.arraycopy(warmup, 0, res, 0, hLen);
        System.arraycopy(SteadyState, 0, res, hLen, tLen);
        return res;
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

    private static double[][] buildCycleStartMatrix(long[][] state, double[] throughput, int len) {
        if (len < 2 || state == null || throughput == null) {
            return new double[0][0];
        }
        double[][] data = new double[len][7];
        for (int i = 0; i < len; i++) {
            data[i][0] = (double) state[i][2];
            data[i][1] = (double) state[i][3];
            data[i][2] = (double) state[i][4];
            data[i][3] = (double) state[i][5];
            data[i][4] = (double) state[i][6];
            data[i][5] = (double) state[i][7];
            data[i][6] = throughput[i];
        }
        return data;
    }

    private static double[][] buildCycleStartCombinedMatrix(
            long[][] warmup,
            double[] warmupTp,
            int hLen,
            long[][] SteadyState,
            double[] SteadyStateTp,
            int tLen,
            long totalObs) {
        if (totalObs == 0L) {
            return new double[0][0];
        }
        if (totalObs <= hLen) {
            return buildCycleStartMatrix(warmup, warmupTp, hLen);
        }
        int totalRows = hLen + tLen;
        if (totalRows < 2) {
            return new double[0][0];
        }
        double[][] data = new double[totalRows][7];
        for (int i = 0; i < hLen; i++) {
            data[i][0] = (double) warmup[i][2];
            data[i][1] = (double) warmup[i][3];
            data[i][2] = (double) warmup[i][4];
            data[i][3] = (double) warmup[i][5];
            data[i][4] = (double) warmup[i][6];
            data[i][5] = (double) warmup[i][7];
            data[i][6] = warmupTp[i];
        }
        for (int i = 0; i < tLen; i++) {
            data[hLen + i][0] = (double) SteadyState[i][2];
            data[hLen + i][1] = (double) SteadyState[i][3];
            data[hLen + i][2] = (double) SteadyState[i][4];
            data[hLen + i][3] = (double) SteadyState[i][5];
            data[hLen + i][4] = (double) SteadyState[i][6];
            data[hLen + i][5] = (double) SteadyState[i][7];
            data[hLen + i][6] = SteadyStateTp[i];
        }
        return data;
    }

    private static double[][] buildBatchProgressMatrix(long[][] state, double[] avgServiceTime, int len) {
        if (len < 2 || state == null || avgServiceTime == null) {
            return new double[0][0];
        }
        double[][] data = new double[len][2];
        for (int i = 0; i < len; i++) {
            data[i][0] = (double) state[i][5];
            data[i][1] = avgServiceTime[i];
        }
        return data;
    }

    private static double[][] buildBatchProgressCombinedMatrix(
            long[][] warmup,
            double[] warmupAvg,
            int hLen,
            long[][] SteadyState,
            double[] SteadyStateAvg,
            int tLen,
            long totalObs) {
        if (totalObs == 0L) {
            return new double[0][0];
        }
        if (totalObs <= hLen) {
            return buildBatchProgressMatrix(warmup, warmupAvg, hLen);
        }
        int totalRows = hLen + tLen;
        if (totalRows < 2) {
            return new double[0][0];
        }
        double[][] data = new double[totalRows][2];
        for (int i = 0; i < hLen; i++) {
            data[i][0] = (double) warmup[i][5];
            data[i][1] = warmupAvg[i];
        }
        for (int i = 0; i < tLen; i++) {
            data[hLen + i][0] = (double) SteadyState[i][5];
            data[hLen + i][1] = SteadyStateAvg[i];
        }
        return data;
    }

    private static double[][] buildBatchCompleteMatrix(
            long[][] state, double[] avgServiceTime, double[] throughput, int len) {
        if (len < 2 || state == null || avgServiceTime == null || throughput == null) {
            return new double[0][0];
        }
        double[][] data = new double[len][3];
        for (int i = 0; i < len; i++) {
            data[i][0] = (double) state[i][5];
            data[i][1] = avgServiceTime[i];
            data[i][2] = throughput[i];
        }
        return data;
    }

    private static double[][] buildBatchCompleteCombinedMatrix(
            long[][] warmup,
            double[] warmupAvg,
            double[] warmupTp,
            int hLen,
            long[][] SteadyState,
            double[] SteadyStateAvg,
            double[] SteadyStateTp,
            int tLen,
            long totalObs) {
        if (totalObs == 0L) {
            return new double[0][0];
        }
        if (totalObs <= hLen) {
            return buildBatchCompleteMatrix(warmup, warmupAvg, warmupTp, hLen);
        }
        int totalRows = hLen + tLen;
        if (totalRows < 2) {
            return new double[0][0];
        }
        double[][] data = new double[totalRows][3];
        for (int i = 0; i < hLen; i++) {
            data[i][0] = (double) warmup[i][5];
            data[i][1] = warmupAvg[i];
            data[i][2] = warmupTp[i];
        }
        for (int i = 0; i < tLen; i++) {
            data[hLen + i][0] = (double) SteadyState[i][5];
            data[hLen + i][1] = SteadyStateAvg[i];
            data[hLen + i][2] = SteadyStateTp[i];
        }
        return data;
    }

    private static double[][] buildDecisionMatrix(long[][] state, double[] smoothedCost, int len) {
        if (len < 2 || state == null || smoothedCost == null) {
            return new double[0][0];
        }
        double[][] data = new double[len][3];
        for (int i = 0; i < len; i++) {
            data[i][0] = (double) state[i][2]; // contentionPolicy
            data[i][1] = (double) state[i][3]; // bodyPolicy
            data[i][2] = smoothedCost[i]; // smoothedBodyCost
        }
        return data;
    }

    private static double[][] buildDecisionCombinedMatrix(
            long[][] warmup,
            double[] warmupCost,
            int hLen,
            long[][] SteadyState,
            double[] SteadyStateCost,
            int tLen,
            long totalObs) {
        if (totalObs == 0L) {
            return new double[0][0];
        }
        if (totalObs <= hLen) {
            return buildDecisionMatrix(warmup, warmupCost, hLen);
        }
        int totalRows = hLen + tLen;
        if (totalRows < 2) {
            return new double[0][0];
        }
        double[][] data = new double[totalRows][3];
        for (int i = 0; i < hLen; i++) {
            data[i][0] = (double) warmup[i][2];
            data[i][1] = (double) warmup[i][3];
            data[i][2] = warmupCost[i];
        }
        for (int i = 0; i < tLen; i++) {
            data[hLen + i][0] = (double) SteadyState[i][2];
            data[hLen + i][1] = (double) SteadyState[i][3];
            data[hLen + i][2] = SteadyStateCost[i];
        }
        return data;
    }
}
