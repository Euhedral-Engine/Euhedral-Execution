package euhedral.benchmarks.core_benchmarks;

import euhedral.benchmarks.core_benchmarks.utils.MandelbrotCanvas;
import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.benchmarks.utils.FractalExecutor;
import euhedral.hashing.HasherApi;
import euhedral.io.config.LatticeConfig;
import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.impl.BaseCloneableObject;
import euhedral.io.reactor.common.EuhedralSubscriber;
import euhedral.io.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@BenchmarkMode({Mode.AverageTime})
public class MandelbrotBenchmark {

    // 8K Resolution 2X SSAA (7680 * 4320 * 4 = 132,710,400 distinct tasks)
    public static final int WIDTH = 7680;
    public static final int HEIGHT = 4320;
    public static final int CANVAS = WIDTH * HEIGHT;
    public static final int ITERATION_CAP = 5_000;
    public static final double BAILOUT_RADIUS_SQ = 1_000_000.0;
    private static final long SEED = HasherApi.BASE_SEED;
    private static final double CENTER_X = -0.743_644_786_0;
    private static final double CENTER_Y = 0.131_825_253_6;
    private static final double H_DIAMETER = 0.000_002_936;

    private static void shuffle(MandelbrotPixel[] pixels) {
        long seed = SEED;
        for (int i = CANVAS - 1; i > 0; i--) {
            int j = (int) MathFunctions.unsignedMultiplyHigh(HasherApi.mix(seed++), i + 1);
            MandelbrotPixel temp = pixels[i];
            temp.randomizeHash(seed);
            pixels[j].randomizeHash(seed++);

            pixels[i] = pixels[j];
            pixels[j] = temp;
        }
    }

    private static void waitOnRender(PaddedLongAdder counters) {
        long sum = 0;
        int spin = 0;
        long log = System.nanoTime();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);

        long now;
        while ((now = System.nanoTime()) < deadline) {
            if ((spin++ & 31) == 0) {
                sum = counters.sum();
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
    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 1, time = 40, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class ReactorMandelbrot {

        private final double[] magnitudes = new double[CANVAS * 4];
        private final int[] escapes = new int[CANVAS * 4];

        private final MandelbrotPixel[] pixels = new MandelbrotPixel[CANVAS];
        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), false, true);
        private Mono<MandelbrotPixel>[] monos = new Mono[CANVAS];

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            String degree = System.getProperty("degree");
            if (degree == null || degree.isBlank()) {
                throw new RuntimeException("degree is not set. Please run with -Ddegree=N");
            }

            MandelbrotCanvas.generate(WIDTH, HEIGHT, CENTER_X, CENTER_Y, H_DIAMETER,
                    ITERATION_CAP, BAILOUT_RADIUS_SQ, Integer.parseInt(degree), this.magnitudes,
                    this.escapes, this.counters,
                    this.pixels);
            shuffle(this.pixels);
            for (int i = 0; i < CANVAS; i++) {
                int id = i;
                this.monos[i] = Mono.fromRunnable(() -> {
                    MandelbrotPixel frame = this.pixels[id];
                    frame.execute();
                    frame.cpu = counters.fromRawIdx(Thread.currentThread().getId());
                    frame.doFinally();
                    blackhole.consume(frame);
                });
            }
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            this.counters.reset();
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void renderSchedulersParallel(Blackhole blackhole) {
            System.out.println("Total Tasks: " + CANVAS * 4);

            Flux.fromArray(this.monos)
                    .flatMap(m -> m.subscribeOn(Schedulers.parallel()),
                            Runtime.getRuntime().availableProcessors())
                    .subscribe();

            waitOnRender(this.counters);
            blackhole.consume(this.escapes);
            blackhole.consume(this.magnitudes);
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void renderSchedulersBoundedElastic(Blackhole blackhole) {
            System.out.println("Total Tasks: " + CANVAS * 4);

            Flux.fromArray(this.monos)
                    .flatMap(m -> m.subscribeOn(Schedulers.boundedElastic()),
                            Runtime.getRuntime().availableProcessors())
                    .subscribe();

            waitOnRender(this.counters);
            blackhole.consume(this.escapes);
            blackhole.consume(this.magnitudes);
        }
    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 1, time = 40, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class EuhedralMandelbrot {

        public final MandelbrotPixel[] pixels = new MandelbrotPixel[CANVAS];
        private final double[] magnitudes = new double[CANVAS * 4];
        private final int[] escapes = new int[CANVAS * 4];

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);

        private BufferedImage outputImage;
        private int[] rawImageBuffer;

        private int degree;
        private String outputDir;
        private String outputFileName;

        private ControlPlaneLattice controlPlane;
        private EuhedralSubscriber subscriber;

        private void makeSub() {
            this.subscriber = new EuhedralSubscriber();
        }

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

            FractalExecutor executor = new FractalExecutor(blackhole);
            BaseCloneableObject base = new BaseCloneableObject(executor);
            LatticeConfig config = LatticeConfig.ofDefaults(base);
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();

            MandelbrotCanvas.generate(WIDTH, HEIGHT, CENTER_X, CENTER_Y, H_DIAMETER,
                    ITERATION_CAP, BAILOUT_RADIUS_SQ, this.degree, this.magnitudes, this.escapes,
                    this.counters,
                    this.pixels);

            shuffle(this.pixels);
            makeSub();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            this.counters.reset();
            makeSub();
            long seed = SEED;
            for (var pixel : this.pixels) {
                pixel.randomizeHash(seed++);
            }
            VarHandle.fullFence();
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void render(Blackhole blackhole) {
            System.out.println("Total Tasks: " + CANVAS * 4);

            Flux.fromArray(this.pixels).subscribe(subscriber);
            this.controlPlane.addUpstream(this.subscriber);

            waitOnRender(this.counters);
            blackhole.consume(this.escapes);
            blackhole.consume(this.magnitudes);
        }

        @TearDown(Level.Trial)
        public void teardown() throws IOException {
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
            System.out.printf("Avg:   %.3f\n", mHist.getMean());
            System.out.printf("P0:    %d\n", mHist.getValueAtPercentile(0));
            System.out.printf("P50:   %d\n", mHist.getValueAtPercentile(50));
            System.out.printf("P90:   %d\n", mHist.getValueAtPercentile(90));
            System.out.printf("P99:   %d\n", mHist.getValueAtPercentile(99));
            System.out.printf("P99.9: %d\n", mHist.getValueAtPercentile(99.9));
            System.out.printf("P100:  %d\n\n", mHist.getValueAtPercentile(100));

            Histogram eHist = escape.getIntervalHistogram();
            System.out.println("Escape Histogram:");
            System.out.printf("Avg:   %.3f\n", eHist.getMean());
            System.out.printf("P0:    %d\n", eHist.getValueAtPercentile(0));
            System.out.printf("P50:   %d\n", eHist.getValueAtPercentile(50));
            System.out.printf("P90:   %d\n", eHist.getValueAtPercentile(90));
            System.out.printf("P99:   %d\n", eHist.getValueAtPercentile(99));
            System.out.printf("P99.9: %d\n", eHist.getValueAtPercentile(99.9));
            System.out.printf("P100:  %d\n", eHist.getValueAtPercentile(100));

            Path path = Paths.get("");
            if (this.outputFileName == null || this.outputFileName.isBlank()) {
                return;
            }
            if (this.outputDir != null && !this.outputDir.isBlank()) {
                path = path.resolve(this.outputDir);
                path.toFile().mkdirs();
            }
            path = path.resolve(this.outputFileName);

            System.out.println("Rendering final image");
            MandelbrotCanvas.render(rawImageBuffer, magnitudes, escapes, degree, ITERATION_CAP,
                    BAILOUT_RADIUS_SQ);

            System.out.printf("Exporting Mandelbrot d=%d symmetry image to disk...\n", this.degree);
            ImageIO.write(this.outputImage, "png", path.toFile());
        }
    }

}
