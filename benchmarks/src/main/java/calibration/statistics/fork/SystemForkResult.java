package calibration.statistics.fork;

import calibration.statistics.VectorField;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CycleStartStatistics;
import calibration.statistics.iteration.DecisionStatistics;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.TransitionAnalysis;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete structured calculation results aggregated across all participating cores and measurement
/// iterations for an entire JMH fork. Represents authoritative calibration telemetry for the fork.
public record SystemForkResult(
        int forkIndex,
        int measurementIterationCount,
        int participatingCoreCount,
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
            "iteration\tscope\tcore\tcycleStartTotal\tbatchProgressTotal\tbatchCompleteTotal\trawBodyCostTotal\tidleDecisionTotal\texecDecisionTotal\tcentroidDistance\n";

    public static final SystemForkResult EMPTY = new SystemForkResult(
            0,
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

    public SystemForkResult {
        Objects.requireNonNull(cycleStart, "cycleStart must not be null");
        Objects.requireNonNull(batchProgress, "batchProgress must not be null");
        Objects.requireNonNull(batchComplete, "batchComplete must not be null");
        Objects.requireNonNull(rawBodyCost, "rawBodyCost must not be null");
        Objects.requireNonNull(idleDecisions, "idleDecisions must not be null");
        Objects.requireNonNull(execDecisions, "execDecisions must not be null");
    }

    public static SystemForkResult empty(int forkIndex, int measurementIterationCount, int participatingCoreCount) {
        return new SystemForkResult(
                forkIndex,
                measurementIterationCount,
                participatingCoreCount,
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
        return -1 + "\tFORK\t"
                + -1 + "\t"
                + cycleStartTotal + "\t"
                + batchProgressTotal + "\t"
                + batchCompleteTotal + "\t"
                + rawBodyCostTotal + "\t"
                + idleDecisionTotal + "\t"
                + execDecisionTotal + "\t"
                + centroidDistance;
    }
}
