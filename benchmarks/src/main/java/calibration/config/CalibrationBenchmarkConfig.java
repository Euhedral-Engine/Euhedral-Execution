package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.config.IdleTimingFunction;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record CalibrationBenchmarkConfig(
        @NonNull List<Integer> cpuSet,
        int parallelSources,
        int orderedSources,
        int workUnits,
        boolean randomizeWork,
        long totalRequiredExecutions,
        long invocationTimeoutMillis,
        int rawSampleLimit,
        boolean observeBatchProgress,
        boolean observeBatchComplete,
        @NonNull Long idleParkNs,
        @NonNull Long contentionHalfLifeNanos,
        @Nullable IdleTimingFunction idleTimingFunction) {

    public static final int DEFAULT_RAW_SAMPLE_LIMIT = 1024;

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
            @JsonProperty("observeBatchProgress") boolean observeBatchProgress,
            @JsonProperty("observeBatchComplete") boolean observeBatchComplete,
            @JsonProperty("idleParkNs") @Nullable Long idleParkNs,
            @JsonProperty("contentionHalfLifeNanos") @Nullable Long contentionHalfLifeNanos,
            @JsonProperty("idleTimingFunction") @Nullable IdleTimingFunction idleTimingFunction) {
        Objects.requireNonNull(cpuSet, "CalibrationBenchmarkConfig cpuSet cannot be null");
        this.cpuSet = List.copyOf(cpuSet);
        this.parallelSources = parallelSources;
        this.orderedSources = orderedSources;
        this.workUnits = workUnits;
        this.randomizeWork = randomizeWork;
        this.totalRequiredExecutions = totalRequiredExecutions;
        this.invocationTimeoutMillis = invocationTimeoutMillis;
        this.rawSampleLimit = rawSampleLimit <= 0 ? DEFAULT_RAW_SAMPLE_LIMIT : rawSampleLimit;
        this.observeBatchProgress = observeBatchProgress;
        this.observeBatchComplete = observeBatchComplete;
        this.idleParkNs = idleParkNs == null ? IdlePolicy.DEFAULT_IDLE_PARK_NS : idleParkNs;
        this.contentionHalfLifeNanos = contentionHalfLifeNanos == null
                ? IdlePolicy.DEFAULT_CONTENTION_HALF_LIFE_NANOS
                : contentionHalfLifeNanos;
        this.idleTimingFunction = idleTimingFunction;
        validate();
    }

    private void validate() {
        if (this.parallelSources + this.orderedSources <= 0) {
            throw new IllegalArgumentException("Number of parallel + ordered sources must be greater than 0.");
        }
        if (this.workUnits < 0) {
            throw new IllegalArgumentException("workUnits must not be negative.");
        }
        if (this.totalRequiredExecutions <= 0) {
            throw new IllegalArgumentException("totalRequiredExecutions must be greater than 0.");
        }
        if (this.invocationTimeoutMillis <= 0) {
            throw new IllegalArgumentException("invocationTimeoutMillis must be greater than 0.");
        }
        toIdlePolicy();
    }

    @JsonIgnore
    public boolean observes() {
        return this.observeBatchProgress || this.observeBatchComplete;
    }

    @JsonIgnore
    public IdlePolicy toIdlePolicy() {
        return this.idleTimingFunction == null
                ? new IdlePolicy(this.idleParkNs, this.contentionHalfLifeNanos)
                : new IdlePolicy(this.idleParkNs, this.contentionHalfLifeNanos, this.idleTimingFunction);
    }
}
