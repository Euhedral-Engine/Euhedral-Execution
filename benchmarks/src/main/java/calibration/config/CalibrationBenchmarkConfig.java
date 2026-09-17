package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.config.IdleTimingFunction;
import io.euhedral_execution.core.control_plane.FragmentControlConfig;
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
        ProductivityGateMode productivityGateMode,
        @Nullable Integer forcedActiveParticipantCount,
        @NonNull Long cacheParkNs,
        @NonNull Long contentionHalfLifeNanos,
        @Nullable IdleTimingFunction cacheTimingFunction,
        @NonNull String cacheActuatorVersion,
        CalibrationLifecycleMode lifecycleMode,
        boolean cacheScarcityGateEnabled) {

    // Schema default for artifacts that predate the timing field; never follow future runtime defaults.
    private static final long HISTORICAL_CONTENTION_HALF_LIFE_NANOS = 1_000_000L;

    @JsonIgnore
    public IdlePolicy toCacheTimingConfig() {
        return new IdlePolicy(cacheParkNs, contentionHalfLifeNanos, cacheTimingFunction);
    }

    public static final int DEFAULT_RAW_SAMPLE_LIMIT = 1024;
    public static final String LEGACY_CACHE_ACTUATOR_VERSION = "legacy-unspecified";

    /// Convenience constructor for the common fixed-fixture calibration shape.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
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
                false);
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
                ProductivityGateMode.AUTO,
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
                ProductivityGateMode.AUTO,
                CalibrationLifecycleMode.RESET);
    }

    /// Backwards-compatible constructor without an explicit productivity-gate mode.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
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
            @Nullable Integer productivityThresholdWeight,
            CalibrationLifecycleMode lifecycleMode) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
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
                ProductivityGateMode.AUTO,
                lifecycleMode);
    }

    /// Backwards-compatible constructor without CACHE participation treatment fields.
    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
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
            @Nullable Integer productivityThresholdWeight,
            ProductivityGateMode productivityGateMode,
            CalibrationLifecycleMode lifecycleMode) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
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
                productivityGateMode,
                null,
                FragmentControlConfig.DEFAULT_CACHE_PARK_NS,
                FragmentControlConfig.CACHE_ACTUATOR_VERSION,
                lifecycleMode);
    }

    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
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
            @Nullable Integer productivityThresholdWeight,
            @Nullable ProductivityGateMode productivityGateMode,
            @Nullable Integer forcedActiveParticipantCount,
            @Nullable Long cacheParkNs,
            @Nullable String cacheActuatorVersion,
            @Nullable CalibrationLifecycleMode lifecycleMode) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
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
                productivityGateMode,
                forcedActiveParticipantCount,
                cacheParkNs,
                IdlePolicy.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                cacheActuatorVersion,
                lifecycleMode);
    }

    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
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
            @Nullable Integer productivityThresholdWeight,
            @Nullable ProductivityGateMode productivityGateMode,
            @Nullable Integer forcedActiveParticipantCount,
            @Nullable Long cacheParkNs,
            @Nullable Long contentionHalfLifeNanos,
            @Nullable String cacheActuatorVersion,
            @Nullable CalibrationLifecycleMode lifecycleMode) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
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
                productivityGateMode,
                forcedActiveParticipantCount,
                cacheParkNs,
                contentionHalfLifeNanos,
                null,
                cacheActuatorVersion,
                lifecycleMode);
    }

    public CalibrationBenchmarkConfig(
            List<Integer> cpuSet,
            int parallelSources,
            int orderedSources,
            int workUnits,
            boolean randomizeWork,
            long totalRequiredExecutions,
            long invocationTimeoutMillis,
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
            @Nullable Integer productivityThresholdWeight,
            @Nullable ProductivityGateMode productivityGateMode,
            @Nullable Integer forcedActiveParticipantCount,
            @Nullable Long cacheParkNs,
            @Nullable Long contentionHalfLifeNanos,
            @Nullable IdleTimingFunction cacheTimingFunction,
            @Nullable String cacheActuatorVersion,
            @Nullable CalibrationLifecycleMode lifecycleMode) {
        this(
                cpuSet,
                parallelSources,
                orderedSources,
                workUnits,
                randomizeWork,
                totalRequiredExecutions,
                invocationTimeoutMillis,
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
                productivityGateMode,
                forcedActiveParticipantCount,
                cacheParkNs,
                contentionHalfLifeNanos,
                cacheTimingFunction,
                cacheActuatorVersion,
                lifecycleMode,
                false);
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
            @JsonProperty("productivityGateMode") @Nullable ProductivityGateMode productivityGateMode,
            @JsonProperty("forcedActiveParticipantCount") @Nullable Integer forcedActiveParticipantCount,
            @JsonProperty("idleParkNs") @Nullable Long cacheParkNs,
            @JsonProperty("contentionHalfLifeNanos") @Nullable Long contentionHalfLifeNanos,
            @JsonProperty("cacheTimingFunction") @Nullable IdleTimingFunction cacheTimingFunction,
            @JsonProperty("cacheActuatorVersion") @Nullable String cacheActuatorVersion,
            @JsonProperty("lifecycleMode") @Nullable CalibrationLifecycleMode lifecycleMode,
            @JsonProperty("cacheScarcityGateEnabled") boolean cacheScarcityGateEnabled) {
        Objects.requireNonNull(cpuSet, "CalibrationBenchmarkConfig cpuSet cannot be null");
        this.cpuSet = List.copyOf(cpuSet);
        this.parallelSources = parallelSources;
        this.orderedSources = orderedSources;
        this.workUnits = workUnits;
        this.randomizeWork = randomizeWork;
        this.totalRequiredExecutions = totalRequiredExecutions;
        this.invocationTimeoutMillis = invocationTimeoutMillis;
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
        this.productivityGateMode = productivityGateMode == null ? ProductivityGateMode.AUTO : productivityGateMode;
        this.forcedActiveParticipantCount = forcedActiveParticipantCount;
        this.cacheTimingFunction = cacheTimingFunction;
        this.cacheScarcityGateEnabled = cacheScarcityGateEnabled;
        this.cacheParkNs = cacheParkNs == null ? FragmentControlConfig.DEFAULT_CACHE_PARK_NS : cacheParkNs;
        this.contentionHalfLifeNanos =
                contentionHalfLifeNanos == null ? HISTORICAL_CONTENTION_HALF_LIFE_NANOS : contentionHalfLifeNanos;
        this.cacheActuatorVersion = cacheActuatorVersion == null ? LEGACY_CACHE_ACTUATOR_VERSION : cacheActuatorVersion;
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
        if (this.pullBucketFork < 0) {
            throw new IllegalArgumentException("pullBucketFork must not be negative");
        }
        if (this.observePullConvoy && this.pullBucketTreatments.isEmpty()) {
            throw new IllegalArgumentException("Pull-convoy observation requires a non-empty treatment order");
        }
        if (this.productivityThresholdWeight != null && this.productivityThresholdWeight < 0) {
            throw new IllegalArgumentException("productivityThresholdWeight must not be negative");
        }
        if (this.productivityGateMode != ProductivityGateMode.AUTO && this.productivityThresholdWeight != null) {
            throw new IllegalArgumentException(
                    "Forced productivityGateMode cannot be combined with productivityThresholdWeight");
        }
        if (this.forcedActiveParticipantCount != null && this.forcedActiveParticipantCount <= 0) {
            throw new IllegalArgumentException("forcedActiveParticipantCount must be positive");
        }
        toCacheTimingConfig();
        if (this.cacheActuatorVersion.isBlank()) {
            throw new IllegalArgumentException("cacheActuatorVersion must not be blank");
        }
        if (this.lifecycleMode == CalibrationLifecycleMode.CONTINUOUS
                && this.pullBucketTreatments.stream()
                        .anyMatch(treatment -> !PullBucketTreatment.BASELINE.equals(treatment))) {
            throw new IllegalArgumentException(
                    "CONTINUOUS lifecycle cannot change pull-bucket treatment between measurement windows");
        }
        boolean observesAnyTelemetry = this.observeCycleStart
                || this.observeBatchProgress
                || this.observeBatchComplete
                || this.observeRawBodyCost
                || this.observeIdleDecision
                || this.observeExecDecision
                || this.observeContentionStaleness
                || this.observePullConvoy;
        if (this.lifecycleMode == CalibrationLifecycleMode.CONTINUOUS
                && observesAnyTelemetry
                && (!this.observeCycleStart
                        || !this.observeIdleDecision
                        || !this.observeExecDecision
                        || !this.observeContentionStaleness)) {
            throw new IllegalArgumentException(
                    "CONTINUOUS lifecycle requires cycle, idle/exec decision, and contention-staleness telemetry");
        }
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
                this.productivityGateMode,
                this.forcedActiveParticipantCount,
                this.cacheParkNs,
                this.contentionHalfLifeNanos,
                this.cacheTimingFunction,
                this.cacheActuatorVersion,
                lifecycleMode,
                this.cacheScarcityGateEnabled);
    }

    /// Resolves a legacy omitted actuator identity to the exact actuator implemented by this runtime.
    public CalibrationBenchmarkConfig withCurrentCacheActuatorIdentity() {
        if (FragmentControlConfig.CACHE_ACTUATOR_VERSION.equals(this.cacheActuatorVersion)) {
            return this;
        }
        if (!LEGACY_CACHE_ACTUATOR_VERSION.equals(this.cacheActuatorVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported cacheActuatorVersion for execution: " + this.cacheActuatorVersion);
        }
        return new CalibrationBenchmarkConfig(
                this.cpuSet,
                this.parallelSources,
                this.orderedSources,
                this.workUnits,
                this.randomizeWork,
                this.totalRequiredExecutions,
                this.invocationTimeoutMillis,
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
                this.productivityGateMode,
                this.forcedActiveParticipantCount,
                this.cacheParkNs,
                this.contentionHalfLifeNanos,
                this.cacheTimingFunction,
                FragmentControlConfig.CACHE_ACTUATOR_VERSION,
                this.lifecycleMode,
                this.cacheScarcityGateEnabled);
    }
}
