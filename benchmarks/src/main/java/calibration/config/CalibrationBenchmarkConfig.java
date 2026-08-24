package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record CalibrationBenchmarkConfig(
        List<Integer> cpuSet,
        int parallelSources,
        int orderedSources,
        int workUnits,
        boolean randomizeWork,
        long totalRequiredExecutions,
        long invocationTimeoutMillis,
        @Nullable String decisionWeightProfile,
        @Nullable FragmentDecisionWeights decisionWeights,
        int rawSampleLimit,
        boolean observeCycleStart,
        boolean observeBatchProgress,
        boolean observeBatchComplete,
        boolean observeRawBodyCost,
        boolean observeIdleDecision,
        boolean observeExecDecision,
        boolean observeContentionStaleness,
        int pullBucketFork,
        List<PullBucketTreatment> pullBucketTreatments,
        boolean observePullConvoy,
        @Nullable Integer productivityThresholdWeight,
        CalibrationLifecycleMode lifecycleMode) {

    public static final int DEFAULT_RAW_SAMPLE_LIMIT = 1024;

    /// Convenience constructor with inline decisionWeights and without decisionWeightProfile.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
            FragmentDecisionWeights decisionWeights,
            int rawSampleLimit,
            boolean observeCycleStart,
            boolean observeBatchProgress,
            boolean observeBatchComplete,
            boolean observeRawBodyCost,
            boolean observeIdleDecision,
            boolean observeExecDecision) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
                null,
                decisionWeights,
                rawSampleLimit,
                observeCycleStart,
                observeBatchProgress,
                observeBatchComplete,
                observeRawBodyCost,
                observeIdleDecision,
                observeExecDecision,
                false,
                0,
                List.of(),
                false,
                null,
                CalibrationLifecycleMode.RESET);
    }

    /// Convenience constructor with decisionWeightProfile reference and without inline decisionWeights.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
            String decisionWeightProfile,
            int rawSampleLimit,
            boolean observeCycleStart,
            boolean observeBatchProgress,
            boolean observeBatchComplete,
            boolean observeRawBodyCost,
            boolean observeIdleDecision,
            boolean observeExecDecision) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
                decisionWeightProfile,
                null,
                rawSampleLimit,
                observeCycleStart,
                observeBatchProgress,
                observeBatchComplete,
                observeRawBodyCost,
                observeIdleDecision,
                observeExecDecision,
                false,
                0,
                List.of(),
                false,
                null,
                CalibrationLifecycleMode.RESET);
    }

    /// Backwards-compatible constructor for callers that specify both profile and inline decision weights.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
            @Nullable String decisionWeightProfile,
            @Nullable FragmentDecisionWeights decisionWeights,
            int rawSampleLimit,
            boolean observeCycleStart,
            boolean observeBatchProgress,
            boolean observeBatchComplete,
            boolean observeRawBodyCost,
            boolean observeIdleDecision,
            boolean observeExecDecision) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
                decisionWeightProfile,
                decisionWeights,
                rawSampleLimit,
                observeCycleStart,
                observeBatchProgress,
                observeBatchComplete,
                observeRawBodyCost,
                observeIdleDecision,
                observeExecDecision,
                false,
                0,
                List.of(),
                false,
                null,
                CalibrationLifecycleMode.RESET);
    }

    /// Backwards-compatible constructor without a productivity threshold override.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
            @Nullable String decisionWeightProfile,
            @Nullable FragmentDecisionWeights decisionWeights,
            int rawSampleLimit,
            boolean observeCycleStart,
            boolean observeBatchProgress,
            boolean observeBatchComplete,
            boolean observeRawBodyCost,
            boolean observeIdleDecision,
            boolean observeExecDecision,
            boolean observeContentionStaleness,
            int pullBucketFork,
            @Nullable List<PullBucketTreatment> pullBucketTreatments,
            boolean observePullConvoy) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
                decisionWeightProfile,
                decisionWeights,
                rawSampleLimit,
                observeCycleStart,
                observeBatchProgress,
                observeBatchComplete,
                observeRawBodyCost,
                observeIdleDecision,
                observeExecDecision,
                observeContentionStaleness,
                pullBucketFork,
                pullBucketTreatments,
                observePullConvoy,
                null,
                CalibrationLifecycleMode.RESET);
    }

    /// Backwards-compatible constructor without an explicit lifecycle mode.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
            @Nullable String decisionWeightProfile,
            @Nullable FragmentDecisionWeights decisionWeights,
            int rawSampleLimit,
            boolean observeCycleStart,
            boolean observeBatchProgress,
            boolean observeBatchComplete,
            boolean observeRawBodyCost,
            boolean observeIdleDecision,
            boolean observeExecDecision,
            boolean observeContentionStaleness,
            int pullBucketFork,
            @Nullable List<PullBucketTreatment> pullBucketTreatments,
            boolean observePullConvoy,
            @Nullable Integer productivityThresholdWeight) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
                decisionWeightProfile,
                decisionWeights,
                rawSampleLimit,
                observeCycleStart,
                observeBatchProgress,
                observeBatchComplete,
                observeRawBodyCost,
                observeIdleDecision,
                observeExecDecision,
                observeContentionStaleness,
                pullBucketFork,
                pullBucketTreatments,
                observePullConvoy,
                productivityThresholdWeight,
                CalibrationLifecycleMode.RESET);
    }

    @JsonCreator
    public CalibrationBenchmarkConfig(
            @JsonProperty("cpuSet") List<Integer> cpuSet,
            @JsonProperty("parallelSources") int parallelSources,
            @JsonProperty("orderedSources") int orderedSources,
            @JsonProperty("workUnits") int workUnits,
            @JsonProperty("randomizeWork") boolean randomizeWork,
            @JsonProperty("totalRequiredExecutions") long totalRequiredExecutions,
            @JsonProperty("invocationTimeoutMillis") long invocationTimeoutMillis,
            @JsonProperty("decisionWeightProfile") @Nullable String decisionWeightProfile,
            @JsonProperty("decisionWeights") @Nullable FragmentDecisionWeights decisionWeights,
            @JsonProperty("rawSampleLimit") int rawSampleLimit,
            @JsonProperty("observeCycleStart") boolean observeCycleStart,
            @JsonProperty("observeBatchProgress") boolean observeBatchProgress,
            @JsonProperty("observeBatchComplete") boolean observeBatchComplete,
            @JsonProperty("observeRawBodyCost") boolean observeRawBodyCost,
            @JsonProperty("observeIdleDecision") boolean observeIdleDecision,
            @JsonProperty("observeExecDecision") boolean observeExecDecision,
            @JsonProperty("observeContentionStaleness") boolean observeContentionStaleness,
            @JsonProperty("pullBucketFork") int pullBucketFork,
            @JsonProperty("pullBucketTreatments") @Nullable List<PullBucketTreatment> pullBucketTreatments,
            @JsonProperty("observePullConvoy") boolean observePullConvoy,
            @JsonProperty("productivityThresholdWeight") @Nullable Integer productivityThresholdWeight,
            @JsonProperty("lifecycleMode") @Nullable CalibrationLifecycleMode lifecycleMode) {
        Objects.requireNonNull(cpuSet, "CalibrationBenchmarkConfig cpuSet cannot be null");
        this.cpuSet = List.copyOf(cpuSet);
        this.parallelSources = parallelSources;
        this.orderedSources = orderedSources;
        this.workUnits = workUnits;
        this.randomizeWork = randomizeWork;
        this.totalRequiredExecutions = totalRequiredExecutions;
        this.invocationTimeoutMillis = invocationTimeoutMillis;
        this.decisionWeightProfile = decisionWeightProfile;
        this.decisionWeights = decisionWeights;
        this.rawSampleLimit = rawSampleLimit <= 0 ? DEFAULT_RAW_SAMPLE_LIMIT : rawSampleLimit;
        this.observeCycleStart = observeCycleStart;
        this.observeBatchProgress = observeBatchProgress;
        this.observeBatchComplete = observeBatchComplete;
        this.observeRawBodyCost = observeRawBodyCost;
        this.observeIdleDecision = observeIdleDecision;
        this.observeExecDecision = observeExecDecision;
        this.observeContentionStaleness = observeContentionStaleness;
        this.pullBucketFork = pullBucketFork;
        this.pullBucketTreatments = pullBucketTreatments == null ? List.of() : List.copyOf(pullBucketTreatments);
        this.observePullConvoy = observePullConvoy;
        this.productivityThresholdWeight = productivityThresholdWeight;
        this.lifecycleMode = lifecycleMode == null ? CalibrationLifecycleMode.RESET : lifecycleMode;
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
        if (this.decisionWeightProfile != null && this.decisionWeightProfile.isBlank()) {
            throw new IllegalArgumentException(
                    "CalibrationBenchmarkConfig decisionWeightProfile cannot be blank if present");
        }
        if (this.decisionWeights == null && this.decisionWeightProfile == null) {
            throw new IllegalArgumentException(
                    "CalibrationBenchmarkConfig must specify either decisionWeights or decisionWeightProfile");
        }
        if (this.pullBucketFork < 0) {
            throw new IllegalArgumentException("pullBucketFork must not be negative");
        }
        if (this.observePullConvoy && this.pullBucketTreatments.isEmpty()) {
            throw new IllegalArgumentException("Pull-convoy observation requires a non-empty treatment order");
        }
        if (this.productivityThresholdWeight != null && this.productivityThresholdWeight < 0) {
            throw new IllegalArgumentException("productivityThresholdWeight must not be negative");
        }
        if (this.lifecycleMode == CalibrationLifecycleMode.CONTINUOUS
                && this.pullBucketTreatments.stream()
                        .anyMatch(treatment -> !PullBucketTreatment.BASELINE.equals(treatment))) {
            throw new IllegalArgumentException(
                    "CONTINUOUS lifecycle cannot change pull-bucket treatment between measurement windows");
        }
        if (this.lifecycleMode == CalibrationLifecycleMode.CONTINUOUS
                && (!this.observeCycleStart
                        || !this.observeIdleDecision
                        || !this.observeExecDecision
                        || !this.observeContentionStaleness)) {
            throw new IllegalArgumentException(
                    "CONTINUOUS lifecycle requires cycle, idle/exec decision, and contention-staleness telemetry");
        }
    }

    /// Returns a copy of this CalibrationBenchmarkConfig with the given decisionWeights set.
    public CalibrationBenchmarkConfig withDecisionWeights(@NonNull FragmentDecisionWeights decisionWeights) {
        Objects.requireNonNull(decisionWeights, "CalibrationBenchmarkConfig decisionWeights cannot be null");
        return new CalibrationBenchmarkConfig(
                this.cpuSet,
                this.parallelSources,
                this.orderedSources,
                this.workUnits,
                this.randomizeWork,
                this.totalRequiredExecutions,
                this.invocationTimeoutMillis,
                this.decisionWeightProfile,
                decisionWeights,
                this.rawSampleLimit,
                this.observeCycleStart,
                this.observeBatchProgress,
                this.observeBatchComplete,
                this.observeRawBodyCost,
                this.observeIdleDecision,
                this.observeExecDecision,
                this.observeContentionStaleness,
                this.pullBucketFork,
                this.pullBucketTreatments,
                this.observePullConvoy,
                this.productivityThresholdWeight,
                this.lifecycleMode);
    }

    /// Returns a copy of this CalibrationBenchmarkConfig with the given decisionWeightProfile reference set.
    public CalibrationBenchmarkConfig withDecisionWeightProfile(@Nullable String decisionWeightProfile) {
        return new CalibrationBenchmarkConfig(
                this.cpuSet,
                this.parallelSources,
                this.orderedSources,
                this.workUnits,
                this.randomizeWork,
                this.totalRequiredExecutions,
                this.invocationTimeoutMillis,
                decisionWeightProfile,
                this.decisionWeights,
                this.rawSampleLimit,
                this.observeCycleStart,
                this.observeBatchProgress,
                this.observeBatchComplete,
                this.observeRawBodyCost,
                this.observeIdleDecision,
                this.observeExecDecision,
                this.observeContentionStaleness,
                this.pullBucketFork,
                this.pullBucketTreatments,
                this.observePullConvoy,
                this.productivityThresholdWeight,
                this.lifecycleMode);
    }

    /// Returns a copy with an explicit measurement lifecycle while preserving the scheduler fixture configuration.
    public CalibrationBenchmarkConfig withLifecycleMode(@NonNull CalibrationLifecycleMode lifecycleMode) {
        Objects.requireNonNull(lifecycleMode, "lifecycleMode must not be null");
        return new CalibrationBenchmarkConfig(
                this.cpuSet,
                this.parallelSources,
                this.orderedSources,
                this.workUnits,
                this.randomizeWork,
                this.totalRequiredExecutions,
                this.invocationTimeoutMillis,
                this.decisionWeightProfile,
                this.decisionWeights,
                this.rawSampleLimit,
                this.observeCycleStart,
                this.observeBatchProgress,
                this.observeBatchComplete,
                this.observeRawBodyCost,
                this.observeIdleDecision,
                this.observeExecDecision,
                this.observeContentionStaleness,
                this.pullBucketFork,
                this.pullBucketTreatments,
                this.observePullConvoy,
                this.productivityThresholdWeight,
                lifecycleMode);
    }
}
