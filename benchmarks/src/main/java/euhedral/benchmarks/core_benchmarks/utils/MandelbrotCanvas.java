package euhedral.benchmarks.core_benchmarks.utils;

import java.util.concurrent.ThreadLocalRandom;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.hashing.HasherApi;

public class MandelbrotCanvas {

    public static final int[] DEFAULT_PALETTE = {0x000766, // 0: Deepest Midnight Blue
            0x011C99, // 1: Royal Blue
            0x0066CC, // 2: Vivid Sea Blue
            0x33CCFF, // 3: Bright Sky Blue
            0x99FFFF, // 4: Pale Ice Cyan
            0xFFFFFF, // 5: Pure White
            0xFFFFCC, // 6: Soft Cream Yellow
            0xFFCC33, // 7: Deep Gold
            0xFF9900, // 8: Bright Orange
            0xCC3300, // 9: Crimson Red
            0x990000, // 10: Dark Maroon
            0x660000, // 11: Dark Auburn Brown
            0x330000, // 12: Espresso Black
            0x000000, // 13: Transition back to Set Edge
            0x000233  // 14: Closing loop back to Deep Blue
    };

    public static void generate(int width, int height, double centerX, double centerY,
            double hDiameter, int iterations, double bailoutRadiusSq, int degree,
            double[] magnitudes, int[] escapes, PaddedLongAdder counters,
            MandelbrotPixel[] pixels) {
        double ySpan = hDiameter / ((double) width / height);

        double xMin = centerX - (hDiameter / 2.0);
        double xMax = centerX + (hDiameter / 2.0);
        double yMin = centerY - (ySpan / 2.0);
        double yMax = centerY + (ySpan / 2.0);

        double pixelWidthStep = (xMax - xMin) / (width - 1);
        double pixelHeightStep = (yMax - yMin) / (height - 1);

        long idHash = ThreadLocalRandom.current().nextLong();
        idHash = HasherApi.mix(idHash);

        for (int y = 0; y < height; y++) {
            double ty = (double) y / (height - 1);
            double ci = yMax - ty * (yMax - yMin);

            int rowOffset = y * width;

            for (int x = 0; x < width; x++) {
                double tx = (double) x / (width - 1);
                double cr = xMin + tx * (xMax - xMin);

                int index = rowOffset + x;

                pixels[index] =
                        new MandelbrotPixel(idHash, null, index, degree, cr, ci, pixelWidthStep,
                                pixelHeightStep, width, height, iterations, bailoutRadiusSq,
                                magnitudes, escapes, counters);
            }
        }
    }

    public static void render(int[] rawImageBuffer, double[] magnitudes, int[] escapes, int degree,
            int iterationCap, double bailoutRadiusSq) {
        for (int index = 0; index < rawImageBuffer.length; index++) {
            int baseOffset = index * 4;
            int rSum = 0, gSum = 0, bSum = 0;

            for (int sample = 0; sample < 4; sample++) {
                int count = escapes[baseOffset + sample];
                int r = 0, g = 0, b = 0;

                if (count < iterationCap) {
                    double modulusSq = magnitudes[baseOffset + sample];
                    if (modulusSq < bailoutRadiusSq) {
                        modulusSq = bailoutRadiusSq;
                    }

                    double logZ = Math.log(modulusSq) * 0.5;
                    double nu = Math.log(logZ) / Math.log(degree);
                    double smoothCount = count + 1.0 - nu;

                    double globalIndex;

                    double t = Math.pow(smoothCount / (double) 5_000, 0.5);
                    double fastOutsideCycle = Math.sin(t * Math.PI * 0.5) * 2.5;

                    globalIndex = fastOutsideCycle * DEFAULT_PALETTE.length;

                    globalIndex %= DEFAULT_PALETTE.length;
                    if (globalIndex < 0) {
                        globalIndex += DEFAULT_PALETTE.length;
                    }

                    int idx1 = (int) globalIndex;
                    int idx2 = (idx1 + 1) % DEFAULT_PALETTE.length;
                    double fraction = globalIndex - idx1;

                    int c1 = DEFAULT_PALETTE[idx1];
                    int c2 = DEFAULT_PALETTE[idx2];

                    int r1 = (c1 >> 16) & 0xFF;
                    int r2 = (c2 >> 16) & 0xFF;
                    int g1 = (c1 >> 8) & 0xFF;
                    int g2 = (c2 >> 8) & 0xFF;
                    int b1 = c1 & 0xFF;
                    int b2 = c2 & 0xFF;

                    r = (int) (r1 * (1.0 - fraction) + r2 * fraction);
                    g = (int) (g1 * (1.0 - fraction) + g2 * fraction);
                    b = (int) (b1 * (1.0 - fraction) + b2 * fraction);
                }

                rSum += r;
                gSum += g;
                bSum += b;
            }

            int finalR = rSum / 4;
            int finalG = gSum / 4;
            int finalB = bSum / 4;

            rawImageBuffer[index] = (finalR << 16) | (finalG << 8) | finalB;
        }
    }
}
