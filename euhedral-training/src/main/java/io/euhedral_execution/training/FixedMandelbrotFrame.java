package io.euhedral_execution.training;

import io.euhedral_execution.core.frames.AbstractFrame;

/// A deterministic Mandelbrot workload used by native policy benchmarks.
///
/// Frame ordinals select from a fixed coordinate palette containing both quickly escaping and
/// boundary/interior points. This preserves reproducibility while varying execution cost across
/// frames presented to the scheduler.
final class FixedMandelbrotFrame extends AbstractFrame {

    private static final int ITERATION_CAP = 5_000;
    private static final double BAILOUT_RADIUS_SQUARED = 1_000_000.0;
    private static final double SAMPLE_OFFSET = 2.0e-10;

    private static final double[] REAL_COORDINATES = {
        2.0, 0.0, -0.75, -0.743_643_887_037_151, -1.25, -0.101_096_363_845_62, 0.285, -0.8
    };
    private static final double[] IMAGINARY_COORDINATES = {
        2.0, 0.0, 0.1, 0.131_825_904_205_33, 0.0, 0.956_286_510_809_14, 0.01, 0.156
    };

    private final double cr;
    private final double ci;

    private int completedIterations;
    private long resultChecksum;

    /// Creates one fixed workload frame for the supplied deterministic palette ordinal.
    private FixedMandelbrotFrame(long idHash, int ordinal) {
        super(idHash);
        int coordinate = Math.floorMod(ordinal, REAL_COORDINATES.length);
        this.cr = REAL_COORDINATES[coordinate];
        this.ci = IMAGINARY_COORDINATES[coordinate];
    }

    /// Generates the requested deterministic workload set while preserving benchmark routing
    /// semantics. Unordered frames derive routing hashes exclusively from `routingSeed`.
    static FixedMandelbrotFrame[] generate(int count, boolean ordered, long idHash, long routingSeed) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        FixedMandelbrotFrame[] frames = new FixedMandelbrotFrame[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new FixedMandelbrotFrame(idHash, i);
            if (!ordered) {
                frames[i].randomizeHash(routingSeed + i);
            }
        }
        return frames;
    }

    /// Computes four fixed subpixel samples and records their deterministic result.
    @Override
    public void execute() {
        int iterations = 0;
        long checksum = 0;
        for (int sample = 0; sample < 4; sample++) {
            double sampleCr = this.cr + ((sample & 1) == 0 ? -SAMPLE_OFFSET : SAMPLE_OFFSET);
            double sampleCi = this.ci + ((sample & 2) == 0 ? -SAMPLE_OFFSET : SAMPLE_OFFSET);
            double zr = 0.0;
            double zi = 0.0;
            int count = 0;

            while (zr * zr + zi * zi < BAILOUT_RADIUS_SQUARED && count < ITERATION_CAP) {
                double nextZr = zr * zr - zi * zi + sampleCr;
                zi = 2.0 * zr * zi + sampleCi;
                zr = nextZr;
                count++;
            }

            double magnitudeSquared = zr * zr + zi * zi;
            iterations += count;
            checksum = Long.rotateLeft(checksum, 13) ^ Double.doubleToRawLongBits(magnitudeSquared) ^ count;
        }
        this.completedIterations = iterations;
        this.resultChecksum = checksum;
    }

    /// Returns the total iterations completed by the most recent execution.
    int completedIterations() {
        return this.completedIterations;
    }

    /// Returns the deterministic checksum produced by the most recent execution.
    long resultChecksum() {
        return this.resultChecksum;
    }
}
