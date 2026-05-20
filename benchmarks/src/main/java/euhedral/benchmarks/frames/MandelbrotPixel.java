package euhedral.benchmarks.frames;

import euhedral.atomics.PaddedLongAdder;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.impl.FrameManager;

public class MandelbrotPixel extends AbstractFrame {

    protected final int width;
    protected final int height;
    protected final int iterationCap;
    protected final double[] magnitudes;
    protected final int[] escapes;

    public final PaddedLongAdder counters;

    private final double bailoutRadiusSq;
    private final int degree;

    private final int taskIndex;
    private final double cr;
    private final double ci;

    private final double pixelWidthStep;
    private final double pixelHeightStep;

    public int cpu;

    public MandelbrotPixel(long idHash, FrameManager<Void, AbstractFrame> recycler,
            int taskIndex, int degree, double cr, double ci,
            double pixelWidthStep, double pixelHeightStep,
            int width, int height, int iterationCap,
            double bailoutRadiusSq, double[] magnitudes, int[] escapes, PaddedLongAdder counters) {
        super(idHash, recycler);
        this.width = width;
        this.height = height;
        this.iterationCap = iterationCap;
        this.magnitudes = magnitudes;
        this.escapes = escapes;
        this.counters = counters;

        this.bailoutRadiusSq = bailoutRadiusSq;
        this.degree = degree;

        this.taskIndex = taskIndex;
        this.cr = cr;
        this.ci = ci;
        this.pixelWidthStep = pixelWidthStep;
        this.pixelHeightStep = pixelHeightStep;
    }

    public int compute() {
        double[] subOffsetsX = {-0.25 * pixelWidthStep, 0.25 * pixelWidthStep, -0.25 * pixelWidthStep, 0.25 * pixelWidthStep};
        double[] subOffsetsY = {-0.25 * pixelHeightStep, -0.25 * pixelHeightStep, 0.25 * pixelHeightStep, 0.25 * pixelHeightStep};

        int baseArrayOffset = this.taskIndex * 4;
        int totalEscapeAccumulator = 0;

        for (int sample = 0; sample < 4; sample++) {
            double subCr = this.cr + subOffsetsX[sample];
            double subCi = this.ci + subOffsetsY[sample];

            double zr = 0.0;
            double zi = 0.0;
            int count = 0;

            switch (degree) {
                case 3: // Z^3 + C
                    while (iterate(zr, zi, count)) {
                        double zr2 = zr * zr;
                        double zi2 = zi * zi;
                        double temp = zr * (zr2 - 3.0 * zi2) + subCr;
                        zi = zi * (3.0 * zr2 - zi2) + subCi;
                        zr = temp;
                        count++;
                    }
                    break;

                case 4: // Z^4 + C
                    while (iterate(zr, zi, count)) {
                        double zr2 = zr * zr - zi * zi;
                        double zi2 = 2.0 * zr * zi;
                        double temp = zr2 * zr2 - zi2 * zi2 + subCr;
                        zi = 2.0 * zr2 * zi2 + subCi;
                        zr = temp;
                        count++;
                    }
                    break;

                case 5: // Z^5 + C
                    while (iterate(zr, zi, count)) {
                        double zr2 = zr * zr - zi * zi;
                        double zi2 = 2.0 * zr * zi;
                        double zr4 = zr2 * zr2 - zi2 * zi2;
                        double zi4 = 2.0 * zr2 * zi2;
                        double temp = zr4 * zr - zi4 * zi + subCr;
                        zi = zr4 * zi + zi4 * zr + subCi;
                        zr = temp;
                        count++;
                    }
                    break;

                case 6: // Z^6 + C
                    while (iterate(zr, zi, count)) {
                        double zr2 = zr * zr - zi * zi;
                        double zi2 = 2.0 * zr * zi;
                        double zr4 = zr2 * zr2 - zi2 * zi2;
                        double zi4 = 2.0 * zr2 * zi2;
                        double temp = zr4 * zr2 - zi4 * zi2 + subCr;
                        zi = zr4 * zi2 + zi4 * zr2 + subCi;
                        zr = temp;
                        count++;
                    }
                    break;

                default:
                    while (iterate(zr, zi, count)) {
                        double currentR = zr;
                        double currentI = zi;
                        double resultR = 1.0;
                        double resultI = 0.0;
                        int n = degree;

                        while (n > 0) {
                            if ((n & 1) == 1) {
                                double temp = resultR * currentR - resultI * currentI;
                                resultI = resultR * currentI + resultI * currentR;
                                resultR = temp;
                            }
                            double tempSq = currentR * currentR - currentI * currentI;
                            currentI = 2.0 * currentR * currentI;
                            currentR = tempSq;
                            n >>>= 1;
                        }
                        zr = resultR + subCr;
                        zi = resultI + subCi;
                        count++;
                    }
                    break;
            }

            this.magnitudes[baseArrayOffset + sample] = zr * zr + zi * zi;
            this.escapes[baseArrayOffset + sample] = count;
            totalEscapeAccumulator += count;
        }

        return totalEscapeAccumulator / 4;
    }

    private boolean iterate(double zr, double zi, int count) {
        return (zr * zr + zi * zi) < bailoutRadiusSq && count < this.iterationCap;
    }

    @Override
    public long getSizeBytes() {
        return 64;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void kill() {

    }

    @Override
    public void doFinally() {
        this.counters.getAndAdd(this.cpu, 4);
    }
}
