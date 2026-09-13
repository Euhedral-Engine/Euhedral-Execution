package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;

/// Macroscopic fields at the represented time of unforced post-collision populations.
public final class FieldExtractor {
    private FieldExtractor() {}

    public record Field(double density, double ux, double uy, double uz, double gaugePressure) {
        public Field {
            if (!Double.isFinite(density)
                    || density <= 0
                    || !Double.isFinite(ux)
                    || !Double.isFinite(uy)
                    || !Double.isFinite(uz)
                    || !Double.isFinite(gaugePressure))
                throw new IllegalArgumentException("field requires finite values and positive density");
        }
    }

    public record Diagnostics(
            long step, double mass, double minDensity, double maxDensity, double maxSpeed, double maxMach) {
        public Diagnostics {
            if (step < 0
                    || !Double.isFinite(mass)
                    || mass <= 0
                    || !Double.isFinite(minDensity)
                    || minDensity <= 0
                    || !Double.isFinite(maxDensity)
                    || maxDensity < minDensity
                    || !Double.isFinite(maxSpeed)
                    || maxSpeed < 0
                    || !Double.isFinite(maxMach)
                    || maxMach < 0) throw new IllegalArgumentException("invalid completed-state diagnostics");
        }
    }

    static Field sample(double[][] populations, GridShape shape, double rho0, long step, int x, int y, int z) {
        double[] values = new double[5];
        read(populations, shape, rho0, step, x, y, z, values);
        return new Field(values[0], values[1], values[2], values[3], values[4]);
    }

    /// One fixed scratch buffer for the entire reduction; no per-cell objects or output arrays.
    static Diagnostics summarize(double[][] populations, GridShape shape, double rho0, long step, StepContext context) {
        double[] values = new double[5];
        double mass = 0, correction = 0, min = Double.POSITIVE_INFINITY, max = 0, speed = 0;
        for (int z = 0; z < shape.nz(); z++) {
            for (int y = 0; y < shape.ny(); y++) {
                for (int x = 0; x < shape.nx(); x++) {
                    if (x % 256 == 0) {
                        if (context != null) context.checkProgress(x, y, z);
                        else if (Thread.currentThread().isInterrupted())
                            throw new SimulationException(step, x, y, z, "interrupted during field extraction");
                    }
                    read(populations, shape, rho0, step, x, y, z, values);
                    double term = values[0] - correction;
                    double sum = mass + term;
                    correction = (sum - mass) - term;
                    mass = sum;
                    if (!Double.isFinite(mass)) throw new SimulationException(step, x, y, z, "non-finite total mass");
                    min = Math.min(min, values[0]);
                    max = Math.max(max, values[0]);
                    double localSpeed = Math.hypot(Math.hypot(values[1], values[2]), values[3]);
                    if (!Double.isFinite(localSpeed * Math.sqrt(3)))
                        throw new SimulationException(step, x, y, z, "non-finite speed/Mach");
                    speed = Math.max(speed, localSpeed);
                }
            }
        }
        return new Diagnostics(step, mass, min, max, speed, speed * Math.sqrt(3));
    }

    private static void read(
            double[][] populations, GridShape shape, double rho0, long step, int x, int y, int z, double[] values) {
        int index = x + shape.nx() * (y + shape.ny() * z);
        double rho = 0, mx = 0, my = 0, mz = 0;
        for (int i = 0; i < D3Q19.Q; i++) {
            double value = populations[i][index];
            if (!Double.isFinite(value))
                throw new SimulationException(step, x, y, z, "non-finite stored population direction=" + i);
            rho += value;
            mx += D3Q19.x(i) * value;
            my += D3Q19.y(i) * value;
            mz += D3Q19.z(i) * value;
        }
        if (!Double.isFinite(rho) || rho <= 0)
            throw new SimulationException(step, x, y, z, "stored density must be finite and positive");
        values[0] = rho;
        values[1] = mx / rho;
        values[2] = my / rho;
        values[3] = mz / rho;
        values[4] = D3Q19.CS2 * (rho - rho0);
        for (int i = 1; i < values.length; i++) {
            if (!Double.isFinite(values[i]))
                throw new SimulationException(step, x, y, z, "non-finite macroscopic field");
        }
    }
}
