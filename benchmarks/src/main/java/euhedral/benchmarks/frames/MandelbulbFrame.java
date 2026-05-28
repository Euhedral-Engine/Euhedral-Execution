package euhedral.benchmarks.frames;

import euhedral.atomics.PaddedLongAdder;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.ThreadTools;
import euhedral.hashing.HasherApi;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.impl.FrameManager;
import java.util.concurrent.ThreadLocalRandom;
import org.openjdk.jmh.infra.Blackhole;

public class MandelbulbFrame extends AbstractFrame {

    public static MandelbulbFrame[][] generate(int rows, int cols, int width, int height,
            double centerX, double centerY, double hDiameter, int maxRaySteps, int iterations,
            double bailoutRadiusSq, Blackhole blackhole, PaddedLongAdder counters, RoutingPolicy policy) {
        CpuInfo info = ThreadTools.getCpuInfo();

        MandelbulbFrame[][] frames = new MandelbulbFrame[rows][cols];

        double ySpan = hDiameter / ((double) width / height);

        double xMin = centerX - (hDiameter / 2.0);
        double xMax = centerX + (hDiameter / 2.0);
        double yMin = centerY - (ySpan / 2.0);
        double yMax = centerY + (ySpan / 2.0);

        long idHash = ThreadLocalRandom.current().nextLong();
        idHash = HasherApi.mix(idHash);

        int rCount = 0;
        int cCount = 0;
        for (int y = 0; y < height; y++) {
            double ty = (double) y / (height - 1);
            double cY = yMax - ty * (yMax - yMin);

            for (int x = 0; x < width; x++) {
                double tx = (double) x / (width - 1);
                double cX = xMin + tx * (xMax - xMin);

                frames[rCount][cCount] = new MandelbulbFrame(idHash, null, cX, cY,
                         width, height, maxRaySteps, iterations, bailoutRadiusSq, blackhole,
                        counters);
                frames[rCount][cCount].setOrigin(info);
                frames[rCount][cCount].setRoutingPolicy(policy);
                cCount++;
                if (cCount == cols) {
                    cCount = 0;
                    rCount++;
                }
            }
        }
        return frames;
    }

    private static final double POWER = 8.0;

    public final PaddedLongAdder counters;
    protected final int width;
    protected final int height;
    protected final int maxRaySteps;
    protected final int iterationCap;
    protected final Blackhole blackhole;
    private final double bailoutRadiusSq;

    private final double x;
    private final double y;

    public int cpu;

    public MandelbulbFrame(long idHash, FrameManager<Void, AbstractFrame> recycler,
            double x, double y,
            int width, int height, int maxRaySteps, int iterationCap,
            double bailoutRadiusSq, Blackhole blackhole, PaddedLongAdder counters) {
        super(idHash, recycler);
        this.width = width;
        this.height = height;
        this.maxRaySteps = maxRaySteps;
        this.iterationCap = iterationCap;
        this.blackhole = blackhole;
        this.counters = counters;

        this.bailoutRadiusSq = bailoutRadiusSq;

        this.x = x;
        this.y = y;
    }

    @Override
    public void execute() {
        int rSum = 0, gSum = 0, bSum = 0;
        int ssaa = 2;

        for(int sx = 0; sx < ssaa; sx++) {
            for(int sy = 0; sy < ssaa; sy++) {
                double px = ((this.x + (sx + 0.5) / ssaa) / this.width) * 2.0 - 1.0;
                double py = ((this.y + (sy + 0.5) / ssaa) / this.height) * 2.0 - 1.0;
                py *= (double) this.height / width;

                // Ray Setup: Origin and direction
                double ox = 0.0, oy = 0.0, oz = -2.5;
                double dx = px, dy = py, dz = 1.2;

                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len;
                dy /= len;
                dz /= len;

                int packedColor = rayMarch(ox, oy, oz, dx, dy, dz);

                rSum += (packedColor >> 16) & 0xFF;
                gSum += (packedColor >> 8) & 0xFF;
                bSum += packedColor & 0xFF;
            }
        }
        rSum = rSum / (ssaa * ssaa);
        gSum = gSum / (ssaa * ssaa);
        bSum = bSum / (ssaa * ssaa);
        blackhole.consume((rSum << 16) | (gSum << 8) | bSum);
    }

    public int rayMarch(double ox, double oy, double oz, double dx, double dy, double dz) {
        double t = 0.0; // Distance traveled along the ray

        for (int step = 0; step < this.maxRaySteps; step++) {
            double px = ox + dx * t;
            double py = oy + dy * t;
            double pz = oz + dz * t;

            double distance = evaluateDistance(px, py, pz);

            // Hit condition: Ray is close enough to the surface
            if(distance < 0.001) {
                int intensity = (int) Math.min(255, (step / (double) this.maxRaySteps) * 510);
                int r = Math.min(255, intensity);
                int g = Math.max(0, intensity - 255);
                int b = (int) (128 + (distance * 10_000)) & 0xFF;
                return (r << 16) | (g << 8) | b;
            }

            t += distance;
            // Miss condition
            if(t > 4.0) {
                break;
            }
        }

        double skyY = 0.5 * (dy + 1.0);
        int rSky = (int)(((1.0 - skyY) * 1.0 + skyY * 0.3) * 255);
        int gSky = (int)(((1.0 - skyY) * 1.0 + skyY * 0.5) * 255);
        int bSky = (int)(((1.0 - skyY) * 1.0 + skyY * 0.8) * 255);
        return  (rSky << 16) | (gSky << 8) | bSky;
    }

    private double evaluateDistance(double px, double py, double pz) {
        double zx = px;
        double zy = py;
        double zz = pz;

        double dr = 1.0; // Derivative tracker for distance estimation
        double r = 0.0; // Radius magnitude

        for (int i = 0; i < this.iterationCap; i++) {
            r = Math.sqrt(zx * zx + zy * zy + zz * zz);
            if(r > this.bailoutRadiusSq) {
                break;
            }

            // Convert Cartesian to Spherical
            double theta = Math.acos(zz / r);
            double phi = Math.atan2(zy, zx);

            dr = Math.pow(r, POWER - 1.0) * POWER * dr + 1.0;

            // Scale and rotate the coordinates
            double zr = Math.pow(r, POWER);
            theta *= POWER;
            phi *= POWER;

            // Convert back to Cartesian
            zx = zr * Math.sin(theta) * Math.cos(phi) + px;
            zy = zr * Math.sin(theta) * Math.cos(phi) + py;
            zz = zr * Math.cos(theta) + pz;
        }
        return 0.5 * Math.log(r) * r / dr;
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
