package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import java.util.Objects;
import java.util.function.LongSupplier;

/// Immutable generation inputs. Current populations are read-only; ranges own next-buffer writes.
public record StepContext(
        GridShape shape,
        double[][] current,
        double[][] next,
        double omega,
        long step,
        long startedNs,
        long timeoutNs,
        LongSupplier clock) {
    public StepContext {
        Objects.requireNonNull(shape);
        Objects.requireNonNull(current);
        Objects.requireNonNull(next);
        Objects.requireNonNull(clock);
        if (!Double.isFinite(omega) || omega <= 0 || omega >= 2 || step <= 0 || timeoutNs <= 0)
            throw new IllegalArgumentException("invalid relaxation, generation, or timeout");
        if (current.length != D3Q19.Q || next.length != D3Q19.Q)
            throw new IllegalArgumentException("D3Q19 needs 19 direction arrays per buffer");
        long cells = shape.cellCount();
        for (int i = 0; i < D3Q19.Q; i++) {
            if (current[i] == null || next[i] == null || current[i].length != cells || next[i].length != cells)
                throw new IllegalArgumentException("population array length differs from grid");
            for (int j = 0; j < D3Q19.Q; j++) {
                if (current[i] == next[j] || (i != j && (current[i] == current[j] || next[i] == next[j])))
                    throw new IllegalArgumentException("population arrays must not alias");
            }
        }
    }

    /// Checked at bounded intervals and before publication; subtraction handles nanoTime wraparound.
    public void checkProgress(int x, int y, int z) {
        if (Thread.currentThread().isInterrupted()) throw new SimulationException(step, x, y, z, "interrupted");
        if (clock.getAsLong() - startedNs >= timeoutNs)
            throw new SimulationException(step, x, y, z, "step deadline exceeded");
    }
}
