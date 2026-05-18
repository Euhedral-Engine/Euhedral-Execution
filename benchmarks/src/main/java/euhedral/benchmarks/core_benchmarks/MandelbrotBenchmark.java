package euhedral.benchmarks.core_benchmarks;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.core_benchmarks.utils.MandelbrotCanvas;
import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.benchmarks.pipelines.FractalPipeline;
import euhedral.benchmarks.pipelines.FractalPublisher;
import euhedral.hashing.HasherApi;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.config.ExecutionManagerConfig.IdleCyclePolicy;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.utils.MathFunctions;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 0, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 40, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class MandelbrotBenchmark {

    // 8K Resolution 2X SSAA (7680 * 4320 * 4 = 132,710,400 distinct tasks)
    public static final int WIDTH = 7680;
    public static final int HEIGHT = 4320;
    public static final int CANVAS = WIDTH * HEIGHT;

    public static final int ITERATION_CAP = 5_000;
    public static final double BAILOUT_RADIUS_SQ = 1_000_000.0;

    private static final double CENTER_X = -0.743_644_786_0;
    private static final double CENTER_Y = 0.131_825_253_6;
    private static final double H_DIAMETER = 0.000_002_936;

    private final double[] magnitudes = new double[CANVAS * 4];
    private final int[] escapes = new int[CANVAS * 4];
    public final MandelbrotPixel[] pixels = new MandelbrotPixel[CANVAS];


    private final FractalPublisher[] publishers =
            new FractalPublisher[Runtime.getRuntime().availableProcessors()];
    private final PaddedLongAdder counters =
            new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);

    private BufferedImage outputImage;
    private int[] rawImageBuffer;

    private int degree;
    private String outputDir;
    private String outputFileName;

    private ControlPlane controlPlane;
    public ExecutorService producerPool;
    public CyclicBarrier barrier = new CyclicBarrier(32 + 1);

    @Setup(Level.Trial)
    public void setup(Blackhole blackhole) {
        this.outputImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        this.rawImageBuffer =
                ((DataBufferInt) this.outputImage.getRaster().getDataBuffer()).getData();
        String degree = System.getProperty("degree");
        if (degree == null || degree.isBlank()) {
            throw new RuntimeException("degree is not set. Please run with -Ddegree=N");
        }
        this.degree = Integer.parseInt(degree);
        this.outputDir = System.getProperty("outputDir");
        this.outputFileName = System.getProperty("outputFile");

        DRRConfig drrConfig = new DRRConfig(null, "mandelbrot", null);
//        ExecutionManagerConfig emConfig = ExecutionManagerConfig.balancedDefault(null, "mandelbrot");
        ExecutionManagerConfig emConfig = new ExecutionManagerConfig(null, 4_096, 1024, false,
                IdleCyclePolicy.DEFAULT, null,
                "mandelbrot");
        FractalPipeline pipeline =
                new FractalPipeline("MandelbrotBenchmark", drrConfig, emConfig, blackhole);
        this.controlPlane = ControlPlane.getOrCreate("MandelbrotBenchmark", pipeline, null);
        this.producerPool = Executors.newFixedThreadPool(32);

        int share = CANVAS / this.publishers.length;
        for (int i = 0; i < this.publishers.length; i++) {
            int start = i * share;
            int end = i == this.publishers.length - 1 ? CANVAS : start + share;
            this.publishers[i] = new FractalPublisher(pixels, start, end);
        }

        MandelbrotCanvas.generate(WIDTH, HEIGHT, CENTER_X, CENTER_Y, H_DIAMETER,
                ITERATION_CAP, BAILOUT_RADIUS_SQ, this.degree, this.magnitudes, this.escapes, this.counters,
                this.pixels);

        // Shuffle the tasks
        long seed = ThreadLocalRandom.current().nextLong();
        for (int i = CANVAS - 1; i > 0; i--) {
            int j = (int) MathFunctions.unsignedMultiplyHigh(HasherApi.mix(seed++), i + 1);
            MandelbrotPixel temp = this.pixels[i];
            this.pixels[i] = this.pixels[j];
            this.pixels[j] = temp;
        }
    }

    @Benchmark
    @OperationsPerInvocation(CANVAS * 4)
    public void EuhedralMandelbrot() throws Exception {
        System.out.println("Total Tasks: " + CANVAS * 4);
        this.barrier.reset();
        this.counters.sumAndReset();
        for (int i = 0; i < this.publishers.length; i++) {
            final int id = i;
            this.producerPool.submit(() -> {
                try {
                    this.publishers[id].reset();
                    this.barrier.await();
                    this.controlPlane.ingest(this.publishers[id]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        this.barrier.await();

        long sum = 0;
        int spin = 0;
        long log = System.nanoTime();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);

        long now;
        while ((now = System.nanoTime()) < deadline) {
            if ((spin++ & 31) == 0) {
                sum = this.counters.sum();
                if (sum >= CANVAS * 4) {
                    break;
                }
                if (now - log >= TimeUnit.SECONDS.toNanos(3)) {
                    System.out.println("Progress: " + sum);
                    log = now;
                }
            }
            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        if (sum < CANVAS) {
            throw new RuntimeException("Stall detected. Pending: " + (CANVAS - sum));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        this.producerPool.close();
        controlPlane.close();

        SingleWriterRecorder mag = new SingleWriterRecorder(3);
        SingleWriterRecorder escape = new SingleWriterRecorder(3);
        for (int i = 0; i < CANVAS; i++) {
            int count = escapes[i];

            mag.recordValue((long) magnitudes[i]);
            escape.recordValue(Math.min(count, ITERATION_CAP));
        }

        Histogram mHist = mag.getIntervalHistogram();
        System.out.println("Magnitude Histogram:");
        System.out.printf("Avg: %.3f\n", mHist.getMean());
        System.out.printf("P0: %d\n", mHist.getValueAtPercentile(0));
        System.out.printf("P50: %d\n", mHist.getValueAtPercentile(50));
        System.out.printf("P90: %d\n", mHist.getValueAtPercentile(90));
        System.out.printf("P99: %d\n", mHist.getValueAtPercentile(99));
        System.out.printf("P99.9: %d\n", mHist.getValueAtPercentile(99.9));
        System.out.printf("P100: %d\n", mHist.getValueAtPercentile(100));

        Histogram eHist = escape.getIntervalHistogram();
        System.out.println("Escape Histogram:");
        System.out.printf("Avg: %.3f\n", eHist.getMean());
        System.out.printf("P0: %d\n", eHist.getValueAtPercentile(0));
        System.out.printf("P50: %d\n", eHist.getValueAtPercentile(50));
        System.out.printf("P90: %d\n", eHist.getValueAtPercentile(90));
        System.out.printf("P99: %d\n", eHist.getValueAtPercentile(99));
        System.out.printf("P99.9: %d\n", eHist.getValueAtPercentile(99.9));
        System.out.printf("P100: %d\n", eHist.getValueAtPercentile(100));

        Path path = Paths.get("");
        if (this.outputFileName == null || this.outputFileName.isBlank()) {
            return;
        }
        if (this.outputDir != null && !this.outputDir.isBlank()) {
            path = path.resolve(this.outputDir);
        }
        path = path.resolve(this.outputFileName);

        System.out.println("Rendering final image");
        MandelbrotCanvas.render(rawImageBuffer, magnitudes, escapes, degree, ITERATION_CAP, BAILOUT_RADIUS_SQ);

        System.out.printf("Exporting Mandelbrot d=%d symmetry image to disk...\n", this.degree);
        ImageIO.write(this.outputImage, "png", path.toFile());
    }

}
