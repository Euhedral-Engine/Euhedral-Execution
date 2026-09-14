package io.euhedral_execution.benchmarks.cfd.validation;

/// Trilinear interpolation excludes samples without eight fluid neighbors. No extrapolation,
/// nearest-solid substitution, or inferred component permutation is permitted.
final class ReferenceSampling {
    private ReferenceSampling() {}

    record Sampled(Snapshot candidate, Snapshot reference, long requestedFluid, long coveredFluid, double coverage) {}

    static Sampled atCandidateCenters(Snapshot c, Snapshot r) {
        var candidate = new Snapshot(c.shape, c.spacing, c.time);
        var reference = new Snapshot(c.shape, c.spacing, r.time);
        long requested = 0, covered = 0;
        int[] lower = new int[3];
        double[] fraction = new double[3];
        int[] dimensions = {r.shape.nx(), r.shape.ny(), r.shape.nz()};
        for (int z = 0; z < c.shape.nz(); z++)
            for (int y = 0; y < c.shape.ny(); y++)
                for (int x = 0; x < c.shape.nx(); x++) {
                    int i = x + c.shape.nx() * (y + c.shape.ny() * z);
                    candidate.ids[i] = reference.ids[i] = c.ids[i];
                    if (c.ids[i] != 0) continue;
                    requested++;
                    double px = (x + .5) * c.spacing / r.spacing - .5;
                    double py = (y + .5) * c.spacing / r.spacing - .5;
                    double pz = (z + .5) * c.spacing / r.spacing - .5;
                    lower[0] = (int) Math.floor(px);
                    lower[1] = (int) Math.floor(py);
                    lower[2] = (int) Math.floor(pz);
                    fraction[0] = px - lower[0];
                    fraction[1] = py - lower[1];
                    fraction[2] = pz - lower[2];
                    boolean valid = true;
                    for (int axis = 0; axis < 3; axis++) {
                        if (lower[axis] == dimensions[axis] - 1 && fraction[axis] == 0) {
                            lower[axis]--;
                            fraction[axis] = 1;
                        }
                        if (lower[axis] < 0 || lower[axis] + 1 >= dimensions[axis]) valid = false;
                    }
                    if (valid)
                        for (int dz = 0; dz < 2; dz++)
                            for (int dy = 0; dy < 2; dy++)
                                for (int dx = 0; dx < 2; dx++) {
                                    int j = lower[0]
                                            + dx
                                            + r.shape.nx() * (lower[1] + dy + r.shape.ny() * (lower[2] + dz));
                                    if (r.ids[j] != 0) valid = false;
                                }
                    if (!valid) {
                        candidate.ids[i] = reference.ids[i] = Integer.MIN_VALUE;
                        continue;
                    }
                    covered++;
                    for (int a = 0; a < 5; a++) {
                        candidate.fields[a][i] = c.fields[a][i];
                        for (int dz = 0; dz < 2; dz++)
                            for (int dy = 0; dy < 2; dy++)
                                for (int dx = 0; dx < 2; dx++) {
                                    int j = lower[0]
                                            + dx
                                            + r.shape.nx() * (lower[1] + dy + r.shape.ny() * (lower[2] + dz));
                                    double weight = (dx == 0 ? 1 - fraction[0] : fraction[0])
                                            * (dy == 0 ? 1 - fraction[1] : fraction[1])
                                            * (dz == 0 ? 1 - fraction[2] : fraction[2]);
                                    reference.fields[a][i] += weight * r.fields[a][j];
                                }
                    }
                }
        return new Sampled(candidate, reference, requested, covered, requested == 0 ? 0 : (double) covered / requested);
    }
}
