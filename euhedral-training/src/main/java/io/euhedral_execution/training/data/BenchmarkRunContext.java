package io.euhedral_execution.training.data;

import java.time.Instant;
import java.util.Objects;

public record BenchmarkRunContext(BenchmarkRunDescriptor descriptor, Instant completedAt) {

    public BenchmarkRunContext {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(descriptor.startedAt())) {
            throw new IllegalArgumentException("Completion precedes run start");
        }
    }
}
