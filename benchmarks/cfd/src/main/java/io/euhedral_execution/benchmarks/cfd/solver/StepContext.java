package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.Guards;
import io.euhedral_execution.benchmarks.cfd.config.Vector3;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
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
        LongSupplier clock,
        GeometryMask geometry,
        Vector3 acceleration,
        double densityReference,
        Guards guards) {
    public StepContext(
            GridShape shape,
            double[][] current,
            double[][] next,
            double omega,
            long step,
            long startedNs,
            long timeoutNs,
            LongSupplier clock) {
        this(
                shape,
                current,
                next,
                omega,
                step,
                startedNs,
                timeoutNs,
                clock,
                GeometryMask.periodic(shape),
                Vector3.ZERO,
                1,
                Guards.DEFAULT);
    }

    public StepContext {
        Objects.requireNonNull(shape);
        Objects.requireNonNull(current);
        Objects.requireNonNull(next);
        Objects.requireNonNull(clock);
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(acceleration);
        Objects.requireNonNull(guards);
        if (geometry.openBoundaries() != null
                && (acceleration.x() != 0 || acceleration.y() != 0 || acceleration.z() != 0))
            throw new IllegalArgumentException("open boundaries require zero body acceleration");
        if (!shape.equals(geometry.shape()) || !Double.isFinite(densityReference) || densityReference <= 0)
            throw new IllegalArgumentException("invalid geometry shape or reference density");
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

    /// Guards stay in the range body even when full-field diagnostic scans are disabled.
    public void checkFields(double rho, double ux, double uy, double uz, int x, int y, int z) {
        validateFields(guards, densityReference, step, rho, ux, uy, uz, x, y, z);
    }

    static void validateFields(
            Guards guards, double rho0, long step, double rho, double ux, double uy, double uz, int x, int y, int z) {
        double speedSquared = ux * ux + uy * uy + uz * uz;
        double limit = guards.maxMach();
        double speedLimit = limit / Math.sqrt(3);
        double limitSquared = speedLimit * speedLimit;
        boolean exceeded;
        if (limitSquared >= Double.MIN_NORMAL && Double.isFinite(limitSquared) && Double.isFinite(speedSquared)) {
            exceeded = speedSquared > limitSquared;
        } else {
            /// Extreme finite guards/velocities can overflow or underflow when squared directly.
            /// Normalize only this uncommon path; NaN and infinity still fail the comparison.
            double xScaled = ux / limit, yScaled = uy / limit, zScaled = uz / limit;
            exceeded = !(3 * (xScaled * xScaled + yScaled * yScaled + zScaled * zScaled) <= 1);
        }
        if (exceeded) {
            double mach = Math.hypot(Math.hypot(ux, uy), uz) * Math.sqrt(3);
            throw new SimulationException(step, x, y, z, "maximum Mach exceeded: " + mach);
        }
        if (!Double.isFinite(rho) || rho <= 0 || Math.abs(rho / rho0 - 1) > guards.maxRelativeDensityVariation())
            throw new SimulationException(
                    step, x, y, z, "relative density variation exceeded or invalid density: " + rho);
    }

    /// Checked at bounded intervals and before publication; subtraction handles nanoTime wraparound.
    public void checkProgress(int x, int y, int z) {
        if (Thread.currentThread().isInterrupted()) throw new SimulationException(step, x, y, z, "interrupted");
        if (clock.getAsLong() - startedNs >= timeoutNs)
            throw new SimulationException(step, x, y, z, "step deadline exceeded");
    }
}
