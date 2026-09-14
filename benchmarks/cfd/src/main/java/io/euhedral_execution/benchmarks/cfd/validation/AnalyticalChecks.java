package io.euhedral_execution.benchmarks.cfd.validation;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.FaceCondition;
import java.util.Map;

/// Independent continuum setup checks supplement, rather than loosen, discrete field limits.
final class AnalyticalChecks {
    private AnalyticalChecks() {}

    static Map<String, Object> check(CfdConfiguration c, Snapshot snapshot, long step) {
        var p = c.physics();
        String solution;
        double scale;
        boolean shear = p.shear() != null;
        boolean channel = !shear
                && p.acceleration().x() != 0
                && p.acceleration().y() == 0
                && p.acceleration().z() == 0
                && c.config().geometry().faces().yMin() == FaceCondition.WALL
                && c.config().geometry().boxes().isEmpty()
                && c.config().geometry().spheres().isEmpty()
                && c.config().geometry().cylinders().isEmpty()
                && c.meshes().isEmpty();
        boolean rest = !shear
                && p.initialVelocity().magnitude() == 0
                && p.acceleration().magnitude() == 0
                && c.config().geometry().faces().openAxis() < 0;
        if (!shear && !channel && !rest) return Map.of("applicable", false);
        double h = snapshot.shape.ny();
        if (shear) {
            solution = "periodic Fourier shear decay";
            scale = p.shear().amplitude();
        } else if (channel) {
            solution = "transient parallel-plate Poiseuille series, halfway walls y=0,H";
            scale = Math.abs(p.acceleration().x()) * h * h / (8 * p.viscosity());
        } else {
            solution = "stationary equilibrium";
            scale = 1;
        }
        scale *= p.units().velocityToPhysical(1);
        double sum = 0, maximum = 0;
        int count = 0;
        for (int z = 0; z < snapshot.shape.nz(); z++)
            for (int y = 0; y < snapshot.shape.ny(); y++)
                for (int x = 0; x < snapshot.shape.nx(); x++) {
                    int i = x + snapshot.shape.nx() * (y + snapshot.shape.ny() * z);
                    if (snapshot.ids[i] != 0) continue;
                    double expected = 0;
                    if (shear) {
                        double ky = 2 * Math.PI * p.shear().modeY() / h;
                        double kz = 2 * Math.PI * p.shear().modeZ() / snapshot.shape.nz();
                        expected = p.shear().amplitude()
                                * Math.sin(ky * y)
                                * Math.cos(kz * z)
                                * Math.exp(-p.viscosity() * (ky * ky + kz * kz) * step);
                    } else if (channel && step > 0) {
                        double py = y + .5;
                        expected = p.acceleration().x() * py * (h - py) / (2 * p.viscosity());
                        for (int n = 1; n < 512; n += 2) {
                            double k = n * Math.PI / h;
                            expected -= 4
                                    * p.acceleration().x()
                                    * h
                                    * h
                                    / (p.viscosity() * Math.pow(Math.PI * n, 3))
                                    * Math.sin(k * py)
                                    * Math.exp(-p.viscosity() * k * k * step);
                        }
                    }
                    double error = Math.abs(snapshot.fields[0][i] - p.units().velocityToPhysical(expected));
                    maximum = Math.max(maximum, error);
                    sum += error * error;
                    count++;
                }
        /// This setup gate catches wrong signs, times and order-one viscosity errors. It is not
        /// the candidate/reference acceptance budget, and cannot establish refinement by itself.
        double limit = rest ? 1e-12 : .05 * Math.abs(scale);
        return Map.of(
                "applicable",
                true,
                "solution",
                solution,
                "rmsError",
                Math.sqrt(sum / count),
                "maxError",
                maximum,
                "scale",
                Math.abs(scale),
                "setupLimit",
                limit,
                "passed",
                maximum <= limit);
    }
}
