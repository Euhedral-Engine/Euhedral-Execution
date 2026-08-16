package calibration.statistics.iteration;

import calibration.statistics.VectorField;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete structured calculation results for a single physical core during one iteration.
public record CoreIterationResult(
        int iterationIndex,
        int core,
        long cycleStartTotal,
        long batchProgressTotal,
        long batchCompleteTotal,
        long rawBodyCostTotal,
        long idleDecisionTotal,
        long execDecisionTotal,
        @NonNull CycleStartStatistics cycleStart,
        @NonNull BatchProgressStatistics batchProgress,
        @NonNull BatchCompleteStatistics batchComplete,
        @NonNull RawBodyCostStatistics rawBodyCost,
        @NonNull DecisionStatistics idleDecisions,
        @NonNull DecisionStatistics execDecisions,
        double centroidDistance) {
    public static final String TSV_HEADER =
            "iteration\tcore\tcycleStartTotal\tbatchProgressTotal\tbatchCompleteTotal\trawBodyCostTotal\tidleDecisionTotal\texecDecisionTotal\tcentroidDistance\n";

    public static final CoreIterationResult EMPTY = new CoreIterationResult(
            0,
            0,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            CycleStartStatistics.EMPTY,
            BatchProgressStatistics.EMPTY,
            BatchCompleteStatistics.EMPTY,
            RawBodyCostStatistics.EMPTY,
            DecisionStatistics.EMPTY,
            DecisionStatistics.EMPTY,
            Double.NaN);

    public CoreIterationResult {
        Objects.requireNonNull(cycleStart, "cycleStart must not be null");
        Objects.requireNonNull(batchProgress, "batchProgress must not be null");
        Objects.requireNonNull(batchComplete, "batchComplete must not be null");
        Objects.requireNonNull(rawBodyCost, "rawBodyCost must not be null");
        Objects.requireNonNull(idleDecisions, "idleDecisions must not be null");
        Objects.requireNonNull(execDecisions, "execDecisions must not be null");
    }

    public static CoreIterationResult empty(int iterationIndex, int core) {
        return new CoreIterationResult(
                iterationIndex,
                core,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                CycleStartStatistics.EMPTY,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                Double.NaN);
    }

    public BranchOccupancyResult idleOccupancy() {
        return idleDecisions.occupancy();
    }

    public BranchOccupancyResult execOccupancy() {
        return execDecisions.occupancy();
    }

    public TransitionAnalysis idleHeadTransitions() {
        return idleDecisions.headTransitions();
    }

    public TransitionAnalysis idleSteadyStateTransitions() {
        return idleDecisions.steadyStateTransitions();
    }

    public TransitionAnalysis execHeadTransitions() {
        return execDecisions.headTransitions();
    }

    public TransitionAnalysis execSteadyStateTransitions() {
        return execDecisions.steadyStateTransitions();
    }

    public VectorField idleHeadVectorField() {
        return idleDecisions.headVectorField();
    }

    public VectorField idleSteadyStateVectorField() {
        return idleDecisions.steadyStateVectorField();
    }

    public VectorField execHeadVectorField() {
        return execDecisions.headVectorField();
    }

    public VectorField execSteadyStateVectorField() {
        return execDecisions.steadyStateVectorField();
    }

    public String toTsvRow() {
        return iterationIndex + "\t"
                + core + "\t"
                + cycleStartTotal + "\t"
                + batchProgressTotal + "\t"
                + batchCompleteTotal + "\t"
                + rawBodyCostTotal + "\t"
                + idleDecisionTotal + "\t"
                + execDecisionTotal + "\t"
                + centroidDistance;
    }
}
