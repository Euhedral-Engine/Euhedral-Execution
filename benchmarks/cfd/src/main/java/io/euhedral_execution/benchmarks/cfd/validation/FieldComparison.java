package io.euhedral_execution.benchmarks.cfd.validation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FieldComparison {
    private FieldComparison() {}

    public record Metric(
            double rmsError,
            double maxError,
            double scaledRmsError,
            double scaledMaxError,
            double acceptanceLimit,
            long samples,
            double worstX,
            double worstY,
            double worstZ,
            boolean passed) {}

    public record Result(
            boolean passed,
            long fluidSamples,
            long excludedSamples,
            long maskMismatches,
            String sampling,
            String pressureAlignment,
            Map<String, Metric> metrics) {}

    public static Result compare(
            Snapshot candidate,
            Snapshot reference,
            ValidationSuite.Gauge gauge,
            Map<String, ValidationSuite.Tolerance> tolerances) {
        ValidationSuite.require(
                candidate.shape.equals(reference.shape) && candidate.spacing == reference.spacing,
                "different grids require a declared convergence fixture and interpolation evidence");
        ValidationSuite.require(Snapshot.sameTime(candidate.time, reference.time), "sample times differ");
        long fluid = 0, solids = 0, mismatch = 0;
        double candidateMean = 0, referenceMean = 0;
        for (int i = 0; i < candidate.ids.length; i++) {
            if (candidate.ids[i] != reference.ids[i]) mismatch++;
            if (candidate.ids[i] == 0 && reference.ids[i] == 0) {
                fluid++;
                candidateMean += candidate.fields[4][i];
                referenceMean += reference.fields[4][i];
            } else solids++;
        }
        ValidationSuite.require(fluid > 0, "comparison contains no common fluid samples");
        double offset = gauge == ValidationSuite.Gauge.FLUID_MEAN ? (candidateMean - referenceMean) / fluid : 0;
        var metrics = new LinkedHashMap<String, Metric>();
        metrics.put("velocity", metric(candidate, reference, 0, 3, 0, tolerances.get("velocity"), fluid));
        metrics.put("density", metric(candidate, reference, 3, 1, 0, tolerances.get("density"), fluid));
        metrics.put("pressure", metric(candidate, reference, 4, 1, offset, tolerances.get("pressure"), fluid));
        double massC = 0, massR = 0;
        double volume = Math.pow(candidate.spacing, 3);
        for (int i = 0; i < candidate.ids.length; i++) {
            if (candidate.ids[i] == 0) massC += candidate.fields[3][i] * volume;
            if (reference.ids[i] == 0) massR += reference.fields[3][i] * volume;
        }
        metrics.put("mass", scalar(Math.abs(massC - massR), tolerances.get("mass")));
        return new Result(
                mismatch == 0 && metrics.values().stream().allMatch(Metric::passed),
                fluid,
                solids,
                mismatch,
                "cell-center comparison domain; exclusions and interpolation coverage are reported separately",
                gauge.name(),
                Map.copyOf(metrics));
    }

    static Metric scalar(double error, ValidationSuite.Tolerance tolerance) {
        return new Metric(
                error,
                error,
                error / tolerance.scale(),
                error / tolerance.scale(),
                tolerance.limit(),
                1,
                0,
                0,
                0,
                Double.isFinite(error) && error <= tolerance.limit());
    }

    private static Metric metric(
            Snapshot c,
            Snapshot r,
            int start,
            int components,
            double offset,
            ValidationSuite.Tolerance tolerance,
            long fluid) {
        double sum = 0, maximum = -1;
        int worst = 0;
        for (int i = 0; i < c.ids.length; i++) {
            if (c.ids[i] != 0 || r.ids[i] != 0) continue;
            double error = 0;
            for (int a = start; a < start + components; a++) {
                double difference = c.fields[a][i] - r.fields[a][i] - offset;
                error = Math.hypot(error, difference);
            }
            sum += error * error;
            if (error > maximum) {
                maximum = error;
                worst = i;
            }
        }
        double rms = Math.sqrt(sum / fluid);
        int x = worst % c.shape.nx(),
                y = worst / c.shape.nx() % c.shape.ny(),
                z = worst / (c.shape.nx() * c.shape.ny());
        return new Metric(
                rms,
                maximum,
                rms / tolerance.scale(),
                maximum / tolerance.scale(),
                tolerance.limit(),
                fluid,
                (x + .5) * c.spacing,
                (y + .5) * c.spacing,
                (z + .5) * c.spacing,
                Double.isFinite(maximum) && Double.isFinite(rms) && maximum <= tolerance.limit());
    }
}
