package io.euhedral_execution.training.data;

import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.MeasurementEncoding;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public record BenchmarkObservation(
        ObservationKey key,
        BenchmarkRunDescriptor run,
        ScheduledPolicy scheduledPolicy,
        ObservationStatus status,
        MeasurementEncoding measurementEncoding,
        Instant startedAt,
        Instant endedAt,
        OptionalLong elapsedNanos,
        OptionalLong completedFrames,
        OptionalDouble throughputFramesPerSecond,
        String failureCode) {

    public BenchmarkObservation {
        Objects.requireNonNull(key);
        Objects.requireNonNull(run);
        Objects.requireNonNull(scheduledPolicy);
        Objects.requireNonNull(status);
        Objects.requireNonNull(measurementEncoding);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(endedAt);
        Objects.requireNonNull(elapsedNanos);
        Objects.requireNonNull(completedFrames);
        Objects.requireNonNull(throughputFramesPerSecond);
        if (failureCode == null || (!failureCode.isEmpty() && !failureCode.matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("Invalid failure code");
        }
        if ((elapsedNanos.isPresent() && elapsedNanos.getAsLong() < 0)
                || (completedFrames.isPresent() && completedFrames.getAsLong() < 0)
                || (throughputFramesPerSecond.isPresent()
                        && (!Double.isFinite(throughputFramesPerSecond.getAsDouble())
                                || throughputFramesPerSecond.getAsDouble() < 0))) {
            throw new IllegalArgumentException("Measurement counters must be non-negative");
        }
        if (!key.benchmarkRunId().equals(run.benchmarkRunId())
                || !key.scenario().equals(run.scenario())
                || !key.policyId().equals(scheduledPolicy.policy().id())
                || key.repetitionNumber() > run.parameters().expectedRepetitions()
                || startedAt.isBefore(run.startedAt())
                || endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Observation identity or time mismatch");
        }
        if (measurementEncoding == MeasurementEncoding.DIRECT_THROUGHPUT
                && run.evidenceOrigin() != EvidenceOrigin.IMPORTED) {
            throw new IllegalArgumentException("Direct throughput is imported only");
        }
        if (measurementEncoding == MeasurementEncoding.DIRECT_THROUGHPUT
                && (elapsedNanos.isPresent() != completedFrames.isPresent()
                        || elapsedNanos.isPresent()
                                && ((elapsedNanos.getAsLong() > 0) != throughputFramesPerSecond.isPresent()))) {
            throw new IllegalArgumentException("Direct measurement fields disagree");
        }
        if (measurementEncoding == MeasurementEncoding.COUNTER_DERIVED) {
            if (elapsedNanos.isEmpty()
                    || completedFrames.isEmpty()
                    || elapsedNanos.getAsLong() < 0
                    || completedFrames.getAsLong() < 0
                    || Duration.between(startedAt, endedAt).toNanos() != elapsedNanos.getAsLong()) {
                throw new IllegalArgumentException("Invalid counter-derived measurement");
            }
            if (elapsedNanos.getAsLong() > 0) {
                double expected = completedFrames.getAsLong() * 1_000_000_000.0 / elapsedNanos.getAsLong();
                if (throughputFramesPerSecond.isEmpty()
                        || Double.doubleToRawLongBits(expected)
                                != Double.doubleToRawLongBits(throughputFramesPerSecond.getAsDouble())) {
                    throw new IllegalArgumentException("Throughput does not match counters");
                }
            }
        }
        if (status == ObservationStatus.SUCCESS
                && (throughputFramesPerSecond.isEmpty()
                        || !Double.isFinite(throughputFramesPerSecond.getAsDouble())
                        || throughputFramesPerSecond.getAsDouble() <= 0
                        || !failureCode.isEmpty())) {
            throw new IllegalArgumentException("Invalid success");
        }
        if (status == ObservationStatus.SUCCESS
                && measurementEncoding == MeasurementEncoding.COUNTER_DERIVED
                && (elapsedNanos.getAsLong() <= 0 || completedFrames.getAsLong() <= 0)) {
            throw new IllegalArgumentException("Successful counters must be positive");
        }
        if (measurementEncoding == MeasurementEncoding.COUNTER_DERIVED
                && elapsedNanos.getAsLong() == 0
                && throughputFramesPerSecond.isPresent()) {
            throw new IllegalArgumentException("Zero elapsed time has no throughput");
        }
        if (status == ObservationStatus.SKIPPED
                && (!startedAt.equals(endedAt)
                        || elapsedNanos.isEmpty()
                        || completedFrames.isEmpty()
                        || elapsedNanos.getAsLong() != 0
                        || completedFrames.getAsLong() != 0
                        || throughputFramesPerSecond.isPresent()
                        || failureCode.isEmpty())) {
            throw new IllegalArgumentException("Invalid skipped observation");
        }
        if ((status == ObservationStatus.TIMEOUT || status == ObservationStatus.FAILED) && failureCode.isEmpty()) {
            throw new IllegalArgumentException("Failure code required");
        }
    }
}
