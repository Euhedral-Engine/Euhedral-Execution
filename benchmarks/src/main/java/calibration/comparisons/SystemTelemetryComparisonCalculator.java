package calibration.comparisons;

import calibration.comparisons.schema.AggregateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.CompatibilityStatus;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.CorrelationComparison;
import calibration.comparisons.schema.OccupancyComparison;
import calibration.comparisons.schema.ScalarComparison;
import calibration.comparisons.schema.TransitionComparison;
import calibration.comparisons.schema.VectorCellComparison;
import calibration.comparisons.schema.VectorFieldComparison;
import calibration.statistics.Band;
import calibration.statistics.VectorCell;
import calibration.statistics.VectorField;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.CycleStartScalars;
import calibration.statistics.iteration.CycleStartStatistics;
import calibration.statistics.iteration.DecisionScalars;
import calibration.statistics.iteration.DecisionStatistics;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.TransitionAnalysis;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Calculates whole-run aggregate telemetry comparisons between baseline and candidate calibration runs.
public final class SystemTelemetryComparisonCalculator {

    private static final String[] SEGMENTS = {"head", "steadyState", "combined"};

    private SystemTelemetryComparisonCalculator() {}

    /// Compares system telemetry between baseline and candidate runs, performing compatibility analysis first.
    public static @Nullable AggregateComparison compare(
            @NonNull CompletedRun baseline, @NonNull CompletedRun candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        ComparisonCompatibility compatibility = ComparisonCompatibilityAnalyzer.analyze(baseline, candidate);
        return compare(baseline, candidate, compatibility);
    }

    /// Compares system telemetry between baseline and candidate runs under the given compatibility verdict.
    public static @Nullable AggregateComparison compare(
            @NonNull CompletedRun baseline,
            @NonNull CompletedRun candidate,
            @NonNull ComparisonCompatibility compatibility) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        return compare(baseline.system(), candidate.system(), compatibility);
    }

    /// Compares authoritative system fork results between baseline and candidate under the given compatibility verdict.
    public static @Nullable AggregateComparison compare(
            @NonNull SystemForkResult baseline,
            @NonNull SystemForkResult candidate,
            @NonNull ComparisonCompatibility compatibility) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");

        if (!compatibility.isComparable() || compatibility.status() == CompatibilityStatus.INCOMPATIBLE) {
            return null;
        }

        OccupancyComparison idleOccupancy = compareOccupancy(
                baseline.idleDecisions().occupancy(), candidate.idleDecisions().occupancy());
        OccupancyComparison execOccupancy = compareOccupancy(
                baseline.execDecisions().occupancy(), candidate.execDecisions().occupancy());

        TransitionComparison idleHeadTransitions = compareTransitionsIfAvailable(
                baseline.idleDecisions().headTransitions(),
                candidate.idleDecisions().headTransitions());
        TransitionComparison idleSteadyStateTransitions = compareTransitionsIfAvailable(
                baseline.idleDecisions().steadyStateTransitions(),
                candidate.idleDecisions().steadyStateTransitions());
        TransitionComparison execHeadTransitions = compareTransitionsIfAvailable(
                baseline.execDecisions().headTransitions(),
                candidate.execDecisions().headTransitions());
        TransitionComparison execSteadyStateTransitions = compareTransitionsIfAvailable(
                baseline.execDecisions().steadyStateTransitions(),
                candidate.execDecisions().steadyStateTransitions());

        VectorFieldComparison idleHeadVectorField = compareVectorFieldIfAvailable(
                baseline.idleDecisions().headVectorField(),
                candidate.idleDecisions().headVectorField());
        VectorFieldComparison idleSteadyStateVectorField = compareVectorFieldIfAvailable(
                baseline.idleDecisions().steadyStateVectorField(),
                candidate.idleDecisions().steadyStateVectorField());
        VectorFieldComparison execHeadVectorField = compareVectorFieldIfAvailable(
                baseline.execDecisions().headVectorField(),
                candidate.execDecisions().headVectorField());
        VectorFieldComparison execSteadyStateVectorField = compareVectorFieldIfAvailable(
                baseline.execDecisions().steadyStateVectorField(),
                candidate.execDecisions().steadyStateVectorField());

        Map<String, ScalarComparison> scalarComparisons = collectScalarComparisons(baseline, candidate);
        Map<String, CorrelationComparison> correlationComparisons = collectCorrelationComparisons(baseline, candidate);

        return new AggregateComparison(
                idleOccupancy,
                execOccupancy,
                idleHeadTransitions,
                idleSteadyStateTransitions,
                execHeadTransitions,
                execSteadyStateTransitions,
                idleHeadVectorField,
                idleSteadyStateVectorField,
                execHeadVectorField,
                execSteadyStateVectorField,
                scalarComparisons,
                correlationComparisons);
    }

    /// Compares two scalar summaries and calculates all metric differences as candidate minus baseline.
    public static @NonNull ScalarComparison compareScalar(
            @NonNull ScalarSummary baseline, @NonNull ScalarSummary candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        double meanDelta = candidate.mean() - baseline.mean();
        double medianDelta = candidate.median() - baseline.median();
        double varianceDelta = candidate.variance() - baseline.variance();
        double standardDeviationDelta = candidate.standardDeviation() - baseline.standardDeviation();
        double cvDelta = candidate.coefficientOfVariation() - baseline.coefficientOfVariation();
        double minDelta = candidate.min() - baseline.min();
        double maxDelta = candidate.max() - baseline.max();
        double p25Delta = candidate.p25() - baseline.p25();
        double p50Delta = candidate.p50() - baseline.p50();
        double p75Delta = candidate.p75() - baseline.p75();
        double p95Delta = candidate.p95() - baseline.p95();
        double iqrDelta = candidate.iqr() - baseline.iqr();
        double normalizedIqrDelta = candidate.normalizedIqr() - baseline.normalizedIqr();
        double p95ToP50RatioDelta = candidate.p95ToP50Ratio() - baseline.p95ToP50Ratio();

        return new ScalarComparison(
                baseline,
                candidate,
                meanDelta,
                medianDelta,
                varianceDelta,
                standardDeviationDelta,
                cvDelta,
                minDelta,
                maxDelta,
                p25Delta,
                p50Delta,
                p75Delta,
                p95Delta,
                iqrDelta,
                normalizedIqrDelta,
                p95ToP50RatioDelta);
    }

    /// Compares two 5x5 branch occupancy results and calculates count/probability deltas and total variation distance.
    public static @NonNull OccupancyComparison compareOccupancy(
            @NonNull BranchOccupancyResult baseline, @NonNull BranchOccupancyResult candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        long[][] baseCounts = baseline.exactCounts();
        long[][] candCounts = candidate.exactCounts();
        if (baseCounts.length != Band.GRID_SIZE || candCounts.length != Band.GRID_SIZE) {
            throw new IllegalArgumentException("Occupancy exactCounts must have " + Band.GRID_SIZE + " rows");
        }

        long[][] countDeltas = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        for (int i = 0; i < Band.GRID_SIZE; i++) {
            if (baseCounts[i] == null
                    || candCounts[i] == null
                    || baseCounts[i].length != Band.GRID_SIZE
                    || candCounts[i].length != Band.GRID_SIZE) {
                throw new IllegalArgumentException(
                        "Occupancy exactCounts row " + i + " must have " + Band.GRID_SIZE + " columns");
            }
            for (int j = 0; j < Band.GRID_SIZE; j++) {
                countDeltas[i][j] = candCounts[i][j] - baseCounts[i][j];
            }
        }

        double[][] baseProbs = baseline.normalizedOccupancy();
        double[][] candProbs = candidate.normalizedOccupancy();
        if (baseProbs.length != Band.GRID_SIZE || candProbs.length != Band.GRID_SIZE) {
            throw new IllegalArgumentException("Occupancy probabilities must have " + Band.GRID_SIZE + " rows");
        }

        double[][] probabilityDeltas = new double[Band.GRID_SIZE][Band.GRID_SIZE];
        double sumAbsDiff = 0.0;
        for (int i = 0; i < Band.GRID_SIZE; i++) {
            if (baseProbs[i] == null
                    || candProbs[i] == null
                    || baseProbs[i].length != Band.GRID_SIZE
                    || candProbs[i].length != Band.GRID_SIZE) {
                throw new IllegalArgumentException(
                        "Occupancy probabilities row " + i + " must have " + Band.GRID_SIZE + " columns");
            }
            for (int j = 0; j < Band.GRID_SIZE; j++) {
                double diff = candProbs[i][j] - baseProbs[i][j];
                probabilityDeltas[i][j] = diff;
                sumAbsDiff += Math.abs(diff);
            }
        }

        double totalVariationDistance = 0.5 * sumAbsDiff;
        double contentionCentroidDelta = candidate.contentionCentroid() - baseline.contentionCentroid();
        double bodyCentroidDelta = candidate.bodyCentroid() - baseline.bodyCentroid();
        double centroidDistance = Math.hypot(contentionCentroidDelta, bodyCentroidDelta);

        double contentionVarianceDelta = candidate.contentionVariance() - baseline.contentionVariance();
        double bodyVarianceDelta = candidate.bodyVariance() - baseline.bodyVariance();
        double covarianceDelta = candidate.contentionBodyCovariance() - baseline.contentionBodyCovariance();
        double radiusDelta = candidate.radius() - baseline.radius();

        return new OccupancyComparison(
                baseline,
                candidate,
                countDeltas,
                probabilityDeltas,
                contentionCentroidDelta,
                bodyCentroidDelta,
                centroidDistance,
                contentionVarianceDelta,
                bodyVarianceDelta,
                covarianceDelta,
                radiusDelta,
                totalVariationDistance);
    }

    /// Compares two 25-state transition analyses and calculates count, probability, self-rate, and oscillation deltas.
    public static @NonNull TransitionComparison compareTransitions(
            @NonNull TransitionAnalysis baseline, @NonNull TransitionAnalysis candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        long[][] baseCounts = baseline.transitionCounts();
        long[][] candCounts = candidate.transitionCounts();
        if (baseCounts.length != Band.TOTAL_STATES || candCounts.length != Band.TOTAL_STATES) {
            throw new IllegalArgumentException("Transition counts must have " + Band.TOTAL_STATES + " rows");
        }

        long[][] countDeltas = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            if (baseCounts[i] == null
                    || candCounts[i] == null
                    || baseCounts[i].length != Band.TOTAL_STATES
                    || candCounts[i].length != Band.TOTAL_STATES) {
                throw new IllegalArgumentException(
                        "Transition counts row " + i + " must have " + Band.TOTAL_STATES + " columns");
            }
            for (int j = 0; j < Band.TOTAL_STATES; j++) {
                countDeltas[i][j] = candCounts[i][j] - baseCounts[i][j];
            }
        }

        double[][] baseProbs = baseline.transitionProbabilities();
        double[][] candProbs = candidate.transitionProbabilities();
        if (baseProbs.length != Band.TOTAL_STATES || candProbs.length != Band.TOTAL_STATES) {
            throw new IllegalArgumentException("Transition probabilities must have " + Band.TOTAL_STATES + " rows");
        }

        double[][] probDeltas = new double[Band.TOTAL_STATES][Band.TOTAL_STATES];
        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            if (baseProbs[i] == null
                    || candProbs[i] == null
                    || baseProbs[i].length != Band.TOTAL_STATES
                    || candProbs[i].length != Band.TOTAL_STATES) {
                throw new IllegalArgumentException(
                        "Transition probabilities row " + i + " must have " + Band.TOTAL_STATES + " columns");
            }
            for (int j = 0; j < Band.TOTAL_STATES; j++) {
                probDeltas[i][j] = candProbs[i][j] - baseProbs[i][j];
            }
        }

        double[] selfRateDeltas = new double[Band.TOTAL_STATES];
        int[] candDominantStates = new int[Band.TOTAL_STATES];
        double[] dominantProbDeltas = new double[Band.TOTAL_STATES];
        for (int s = 0; s < Band.TOTAL_STATES; s++) {
            selfRateDeltas[s] = candidate.selfTransitionRate(s) - baseline.selfTransitionRate(s);
            candDominantStates[s] = candidate.dominantOutgoingState(s);
            dominantProbDeltas[s] = candidate.dominantOutgoingProbability(s) - baseline.dominantOutgoingProbability(s);
        }

        double[][] oscDeltas = new double[Band.TOTAL_STATES][Band.TOTAL_STATES];
        for (int a = 0; a < Band.TOTAL_STATES; a++) {
            for (int b = 0; b < Band.TOTAL_STATES; b++) {
                if (a != b) {
                    oscDeltas[a][b] = candidate.oscillation(a, b) - baseline.oscillation(a, b);
                } else {
                    oscDeltas[a][b] = 0.0;
                }
            }
        }

        return new TransitionComparison(
                baseline,
                candidate,
                countDeltas,
                probDeltas,
                selfRateDeltas,
                candDominantStates,
                dominantProbDeltas,
                oscDeltas);
    }

    /// Compares two 5x5 vector fields across all 25 source cells.
    public static @NonNull VectorFieldComparison compareVectorField(
            @NonNull VectorField baseline, @NonNull VectorField candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        VectorCellComparison[][] cells = new VectorCellComparison[Band.GRID_SIZE][Band.GRID_SIZE];
        for (int c = 0; c < Band.GRID_SIZE; c++) {
            for (int b = 0; b < Band.GRID_SIZE; b++) {
                VectorCell baseCell = baseline.cell(c, b);
                VectorCell candCell = candidate.cell(c, b);

                long countDelta = candCell.transitionCount() - baseCell.transitionCount();
                double deltaCDelta = candCell.meanDeltaContention() - baseCell.meanDeltaContention();
                double deltaBDelta = candCell.meanDeltaBody() - baseCell.meanDeltaBody();
                double magDelta = candCell.magnitude() - baseCell.magnitude();

                cells[c][b] = new VectorCellComparison(
                        c, b, baseCell, candCell, countDelta, deltaCDelta, deltaBDelta, magDelta);
            }
        }

        return new VectorFieldComparison(baseline, candidate, cells);
    }

    /// Compares matching correlation matrices, aligning variables by name when necessary.
    public static @NonNull CorrelationComparison compareCorrelation(
            @NonNull CorrelationResult baseline, @NonNull CorrelationResult candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        String[] baseCols = baseline.columnNames();
        String[] candCols = candidate.columnNames();

        if (!Arrays.equals(baseCols, candCols)) {
            if (baseCols.length != candCols.length) {
                throw new IllegalArgumentException("Correlation variable count mismatch: baseline has "
                        + baseCols.length + " variables, candidate has " + candCols.length);
            }
            Map<String, Integer> candColMap = new HashMap<>();
            for (int i = 0; i < candCols.length; i++) {
                candColMap.put(candCols[i], i);
            }
            int[] candIndex = new int[baseCols.length];
            for (int i = 0; i < baseCols.length; i++) {
                Integer idx = candColMap.get(baseCols[i]);
                if (idx == null) {
                    throw new IllegalArgumentException(
                            "Correlation variable mismatch: candidate missing variable '" + baseCols[i] + "'");
                }
                candIndex[i] = idx;
            }

            int n = baseCols.length;
            double[][] baseP = baseline.pearsonMatrix();
            double[][] candP = candidate.pearsonMatrix();
            double[][] baseS = baseline.spearmanMatrix();
            double[][] candS = candidate.spearmanMatrix();

            double[][] pDeltas = new double[n][n];
            double[][] sDeltas = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    pDeltas[i][j] = candP[candIndex[i]][candIndex[j]] - baseP[i][j];
                    sDeltas[i][j] = candS[candIndex[i]][candIndex[j]] - baseS[i][j];
                }
            }
            return new CorrelationComparison(baseline, candidate, baseCols, pDeltas, sDeltas);
        }

        int n = baseCols.length;
        double[][] baseP = baseline.pearsonMatrix();
        double[][] candP = candidate.pearsonMatrix();
        double[][] baseS = baseline.spearmanMatrix();
        double[][] candS = candidate.spearmanMatrix();

        double[][] pDeltas = new double[n][n];
        double[][] sDeltas = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                pDeltas[i][j] = candP[i][j] - baseP[i][j];
                sDeltas[i][j] = candS[i][j] - baseS[i][j];
            }
        }
        return new CorrelationComparison(baseline, candidate, baseCols, pDeltas, sDeltas);
    }

    /// Calculates the idle-vs-execution centroid distance difference between candidate and baseline.
    public static double compareCentroidDistance(
            @NonNull SystemForkResult baseline, @NonNull SystemForkResult candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        return candidate.centroidDistance() - baseline.centroidDistance();
    }

    private static @Nullable TransitionComparison compareTransitionsIfAvailable(
            @Nullable TransitionAnalysis baseline, @Nullable TransitionAnalysis candidate) {
        if (!hasTransitions(baseline) || !hasTransitions(candidate)) {
            return null;
        }
        return compareTransitions(baseline, candidate);
    }

    private static @Nullable VectorFieldComparison compareVectorFieldIfAvailable(
            @Nullable VectorField baseline, @Nullable VectorField candidate) {
        if (!hasVectors(baseline) || !hasVectors(candidate)) {
            return null;
        }
        return compareVectorField(baseline, candidate);
    }

    private static boolean hasTransitions(@Nullable TransitionAnalysis ta) {
        if (ta == null) {
            return false;
        }
        long[][] counts = ta.transitionCounts();
        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            for (int j = 0; j < Band.TOTAL_STATES; j++) {
                if (counts[i][j] > 0L) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasVectors(@Nullable VectorField vf) {
        if (vf == null) {
            return false;
        }
        for (int c = 0; c < Band.GRID_SIZE; c++) {
            for (int b = 0; b < Band.GRID_SIZE; b++) {
                VectorCell cell = vf.cell(c, b);
                if (cell != null && cell.hasVector()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, ScalarComparison> collectScalarComparisons(
            SystemForkResult baseline, SystemForkResult candidate) {
        Map<String, ScalarComparison> map = new LinkedHashMap<>();

        // CycleStart scalars
        CycleStartStatistics bCycle = baseline.cycleStart();
        CycleStartStatistics cCycle = candidate.cycleStart();
        for (String seg : SEGMENTS) {
            CycleStartScalars bScalars =
                    switch (seg) {
                        case "head" -> bCycle.head();
                        case "steadyState" -> bCycle.steadyState();
                        default -> bCycle.combined();
                    };
            CycleStartScalars cScalars =
                    switch (seg) {
                        case "head" -> cCycle.head();
                        case "steadyState" -> cCycle.steadyState();
                        default -> cCycle.combined();
                    };
            putScalarIfAvailable(map, "cycleStart." + seg + ".completed", bScalars.completed(), cScalars.completed());
            putScalarIfAvailable(map, "cycleStart." + seg + ".batchSize", bScalars.batchSize(), cScalars.batchSize());
            putScalarIfAvailable(
                    map, "cycleStart." + seg + ".upstreamCount", bScalars.upstreamCount(), cScalars.upstreamCount());
            putScalarIfAvailable(
                    map,
                    "cycleStart." + seg + ".registeredWorkers",
                    bScalars.registeredWorkers(),
                    cScalars.registeredWorkers());
            putScalarIfAvailable(
                    map, "cycleStart." + seg + ".workerRank", bScalars.workerRank(), cScalars.workerRank());
            putScalarIfAvailable(
                    map, "cycleStart." + seg + ".contention", bScalars.contention(), cScalars.contention());
            putScalarIfAvailable(
                    map, "cycleStart." + seg + ".throughput", bScalars.throughput(), cScalars.throughput());
        }

        // BatchProgress scalars
        BatchProgressStatistics bProgress = baseline.batchProgress();
        BatchProgressStatistics cProgress = candidate.batchProgress();
        for (String seg : SEGMENTS) {
            BatchProgressScalars bScalars =
                    switch (seg) {
                        case "head" -> bProgress.head();
                        case "steadyState" -> bProgress.steadyState();
                        default -> bProgress.combined();
                    };
            BatchProgressScalars cScalars =
                    switch (seg) {
                        case "head" -> cProgress.head();
                        case "steadyState" -> cProgress.steadyState();
                        default -> cProgress.combined();
                    };
            putScalarIfAvailable(
                    map, "batchProgress." + seg + ".upstreamCount", bScalars.upstreamCount(), cScalars.upstreamCount());
            putScalarIfAvailable(
                    map,
                    "batchProgress." + seg + ".registeredWorkers",
                    bScalars.registeredWorkers(),
                    cScalars.registeredWorkers());
            putScalarIfAvailable(
                    map, "batchProgress." + seg + ".workerRank", bScalars.workerRank(), cScalars.workerRank());
            putScalarIfAvailable(
                    map, "batchProgress." + seg + ".contention", bScalars.contention(), cScalars.contention());
            putScalarIfAvailable(
                    map,
                    "batchProgress." + seg + ".avgServiceTime",
                    bScalars.avgServiceTime(),
                    cScalars.avgServiceTime());
        }

        // BatchComplete scalars
        BatchCompleteStatistics bComplete = baseline.batchComplete();
        BatchCompleteStatistics cComplete = candidate.batchComplete();
        for (String seg : SEGMENTS) {
            BatchCompleteScalars bScalars =
                    switch (seg) {
                        case "head" -> bComplete.head();
                        case "steadyState" -> bComplete.steadyState();
                        default -> bComplete.combined();
                    };
            BatchCompleteScalars cScalars =
                    switch (seg) {
                        case "head" -> cComplete.head();
                        case "steadyState" -> cComplete.steadyState();
                        default -> cComplete.combined();
                    };
            putScalarIfAvailable(
                    map, "batchComplete." + seg + ".upstreamCount", bScalars.upstreamCount(), cScalars.upstreamCount());
            putScalarIfAvailable(
                    map,
                    "batchComplete." + seg + ".registeredWorkers",
                    bScalars.registeredWorkers(),
                    cScalars.registeredWorkers());
            putScalarIfAvailable(
                    map, "batchComplete." + seg + ".workerRank", bScalars.workerRank(), cScalars.workerRank());
            putScalarIfAvailable(
                    map, "batchComplete." + seg + ".contention", bScalars.contention(), cScalars.contention());
            putScalarIfAvailable(
                    map,
                    "batchComplete." + seg + ".avgServiceTime",
                    bScalars.avgServiceTime(),
                    cScalars.avgServiceTime());
            putScalarIfAvailable(
                    map, "batchComplete." + seg + ".throughput", bScalars.throughput(), cScalars.throughput());
        }

        // RawBodyCost scalars
        RawBodyCostStatistics bBody = baseline.rawBodyCost();
        RawBodyCostStatistics cBody = candidate.rawBodyCost();
        putScalarIfAvailable(map, "rawBodyCost.head.cost", bBody.head(), cBody.head());
        putScalarIfAvailable(map, "rawBodyCost.steadyState.cost", bBody.steadyState(), cBody.steadyState());
        putScalarIfAvailable(map, "rawBodyCost.combined.cost", bBody.combined(), cBody.combined());

        // IdleDecision scalars
        DecisionStatistics bIdle = baseline.idleDecisions();
        DecisionStatistics cIdle = candidate.idleDecisions();
        for (String seg : SEGMENTS) {
            DecisionScalars bScalars =
                    switch (seg) {
                        case "head" -> bIdle.head();
                        case "steadyState" -> bIdle.steadyState();
                        default -> bIdle.combined();
                    };
            DecisionScalars cScalars =
                    switch (seg) {
                        case "head" -> cIdle.head();
                        case "steadyState" -> cIdle.steadyState();
                        default -> cIdle.combined();
                    };
            putScalarIfAvailable(
                    map, "idleDecisions." + seg + ".contention", bScalars.contention(), cScalars.contention());
            putScalarIfAvailable(
                    map,
                    "idleDecisions." + seg + ".smoothedBodyCost",
                    bScalars.smoothedBodyCost(),
                    cScalars.smoothedBodyCost());
        }

        // ExecDecision scalars
        DecisionStatistics bExec = baseline.execDecisions();
        DecisionStatistics cExec = candidate.execDecisions();
        for (String seg : SEGMENTS) {
            DecisionScalars bScalars =
                    switch (seg) {
                        case "head" -> bExec.head();
                        case "steadyState" -> bExec.steadyState();
                        default -> bExec.combined();
                    };
            DecisionScalars cScalars =
                    switch (seg) {
                        case "head" -> cExec.head();
                        case "steadyState" -> cExec.steadyState();
                        default -> cExec.combined();
                    };
            putScalarIfAvailable(
                    map, "execDecisions." + seg + ".contention", bScalars.contention(), cScalars.contention());
            putScalarIfAvailable(
                    map,
                    "execDecisions." + seg + ".smoothedBodyCost",
                    bScalars.smoothedBodyCost(),
                    cScalars.smoothedBodyCost());
        }

        return map;
    }

    private static void putScalarIfAvailable(
            Map<String, ScalarComparison> map, String key, ScalarSummary base, ScalarSummary cand) {
        if (base != null && cand != null && !base.isEmpty() && !cand.isEmpty()) {
            map.put(key, compareScalar(base, cand));
        }
    }

    private static Map<String, CorrelationComparison> collectCorrelationComparisons(
            SystemForkResult baseline, SystemForkResult candidate) {
        Map<String, CorrelationComparison> map = new LinkedHashMap<>();

        // CycleStart correlations
        putCorrelationIfAvailable(
                map,
                "cycleStart.head",
                baseline.cycleStart().headCorrelations(),
                candidate.cycleStart().headCorrelations());
        putCorrelationIfAvailable(
                map,
                "cycleStart.steadyState",
                baseline.cycleStart().steadyStateCorrelations(),
                candidate.cycleStart().steadyStateCorrelations());
        putCorrelationIfAvailable(
                map,
                "cycleStart.combined",
                baseline.cycleStart().combinedCorrelations(),
                candidate.cycleStart().combinedCorrelations());

        // BatchProgress correlations
        putCorrelationIfAvailable(
                map,
                "batchProgress.head",
                baseline.batchProgress().headCorrelations(),
                candidate.batchProgress().headCorrelations());
        putCorrelationIfAvailable(
                map,
                "batchProgress.steadyState",
                baseline.batchProgress().steadyStateCorrelations(),
                candidate.batchProgress().steadyStateCorrelations());
        putCorrelationIfAvailable(
                map,
                "batchProgress.combined",
                baseline.batchProgress().combinedCorrelations(),
                candidate.batchProgress().combinedCorrelations());

        // BatchComplete correlations
        putCorrelationIfAvailable(
                map,
                "batchComplete.head",
                baseline.batchComplete().headCorrelations(),
                candidate.batchComplete().headCorrelations());
        putCorrelationIfAvailable(
                map,
                "batchComplete.steadyState",
                baseline.batchComplete().steadyStateCorrelations(),
                candidate.batchComplete().steadyStateCorrelations());
        putCorrelationIfAvailable(
                map,
                "batchComplete.combined",
                baseline.batchComplete().combinedCorrelations(),
                candidate.batchComplete().combinedCorrelations());

        // IdleDecision correlations
        putCorrelationIfAvailable(
                map,
                "idleDecisions.head",
                baseline.idleDecisions().headCorrelations(),
                candidate.idleDecisions().headCorrelations());
        putCorrelationIfAvailable(
                map,
                "idleDecisions.steadyState",
                baseline.idleDecisions().steadyStateCorrelations(),
                candidate.idleDecisions().steadyStateCorrelations());
        putCorrelationIfAvailable(
                map,
                "idleDecisions.combined",
                baseline.idleDecisions().combinedCorrelations(),
                candidate.idleDecisions().combinedCorrelations());

        // ExecDecision correlations
        putCorrelationIfAvailable(
                map,
                "execDecisions.head",
                baseline.execDecisions().headCorrelations(),
                candidate.execDecisions().headCorrelations());
        putCorrelationIfAvailable(
                map,
                "execDecisions.steadyState",
                baseline.execDecisions().steadyStateCorrelations(),
                candidate.execDecisions().steadyStateCorrelations());
        putCorrelationIfAvailable(
                map,
                "execDecisions.combined",
                baseline.execDecisions().combinedCorrelations(),
                candidate.execDecisions().combinedCorrelations());

        return map;
    }

    private static void putCorrelationIfAvailable(
            Map<String, CorrelationComparison> map, String key, CorrelationResult base, CorrelationResult cand) {
        if (base != null && cand != null && !base.isEmpty() && !cand.isEmpty()) {
            map.put(key, compareCorrelation(base, cand));
        }
    }
}
