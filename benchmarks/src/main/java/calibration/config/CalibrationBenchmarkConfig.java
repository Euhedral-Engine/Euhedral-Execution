package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.Objects;

public record CalibrationBenchmarkConfig(
        int parallelSources,
        int orderedSources,
        int workUnits,
        boolean randomizeWork,
        long totalRequiredExecutions,
        long invocationTimeoutMillis,
        FragmentDecisionWeights decisionWeights,
        boolean observeCycleStart,
        boolean observeBatchProgress,
        boolean observeBatchComplete,
        boolean observeRawBodyCost,
        boolean observeIdleDecision,
        boolean observeExecDecision) {

    @JsonCreator
    public CalibrationBenchmarkConfig(
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
            FragmentDecisionWeights decisionWeights,
            boolean observeCycleStart,
            boolean observeBatchProgress,
            boolean observeBatchComplete,
            boolean observeRawBodyCost,
            boolean observeIdleDecision,
            boolean observeExecDecision) {
        this.parallelSources = parallelSources;
        this.orderedSources = orderedSources;
        this.workUnits = workUnits;
        this.randomizeWork = randomizeWork;
        this.totalRequiredExecutions = totalRequiredExecutions;
        this.invocationTimeoutMillis = invocationTimeoutMillis;
        this.decisionWeights = decisionWeights;
        this.observeCycleStart = observeCycleStart;
        this.observeBatchProgress = observeBatchProgress;
        this.observeBatchComplete = observeBatchComplete;
        this.observeRawBodyCost = observeRawBodyCost;
        this.observeIdleDecision = observeIdleDecision;
        this.observeExecDecision = observeExecDecision;
        validate();
    }

    private void validate() {
        if (this.parallelSources + this.orderedSources <= 0) {
            throw new IllegalArgumentException("Number of parallel + ordered sources must be greater than 0.");
        }
        if (this.totalRequiredExecutions <= 0) {
            throw new IllegalArgumentException("totalRequiredExecutions must be greater than 0.");
        }
        if (this.invocationTimeoutMillis <= 0) {
            throw new IllegalArgumentException("invocationTimeoutMillis must be greater than 0.");
        }
        Objects.requireNonNull(this.decisionWeights);
    }
}
