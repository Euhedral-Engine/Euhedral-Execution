package io.euhedral_execution.benchmarks.core_benchmarks;

import io.euhedral_execution.benchmarks.core_benchmarks.utils.MandelbrotCanvas;
import io.euhedral_execution.benchmarks.frames.MandelbrotPixel;
import io.euhedral_execution.benchmarks.utils.FractalExecutor;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.reactor.common.EuhedralSubscriber;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class MandelbrotBenchmark {

    // 8K Resolution 2X SSAA (7680 * 4320 * 4 = 132,710,400 distinct tasks)
    public static final int WIDTH = 7680;
    public static final int HEIGHT = 4320;
    public static final int CANVAS = WIDTH * HEIGHT;

    public static final int ITERATION_CAP = 5_000;
    public static final double BAILOUT_RADIUS_SQ = 1_000_000.0;
    private static final long EXPECTED_OPERATIONS = (long) CANVAS * 4;

    private static final Logger LOGGER = LoggerFactory.getLogger(MandelbrotBenchmark.class);
    private static final long SEED = HasherApi.BASE_SEED;
    private static final String TOTAL_TASKS = "Total Tasks: " + (CANVAS * 4);

    private static final double CENTER_X = -0.743_644_786_0;
    private static final double CENTER_Y = 0.131_825_253_6;
    private static final double H_DIAMETER = 0.000_002_936;

    private MandelbrotBenchmark() {}

    private static void shuffle(MandelbrotPixel[] pixels) {
        long seed = SEED;
        for (int i = CANVAS - 1; i > 0; i--) {
            int j = (int) MathFunctions.unsignedMultiplyHigh(HasherApi.mix(seed++), i + 1L);
            MandelbrotPixel temp = pixels[i];
            temp.randomizeHash(seed);
            pixels[j].randomizeHash(seed++);

            pixels[i] = pixels[j];
            pixels[j] = temp;
        }
    }

    private static void waitOnRender(PaddedLongAdder counters) {
        MandelbrotCompletion.await(counters, EXPECTED_OPERATIONS, TimeUnit.MINUTES.toNanos(5), LOGGER);
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
        private final ThreadLocal<Integer> counterSlot = ThreadLocal.withInitial(
                () -> counters.fromRawIdx(Thread.currentThread().threadId()));
        private Mono<Void> parallelPipeline;
        private Mono<Void> boundedElasticPipeline;

        private void execute(MandelbrotPixel frame, Blackhole blackhole) {
            frame.execute();
            frame.cpu = this.counterSlot.get();
            frame.doFinally();
            blackhole.consume(frame);
        }

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            String degree = System.getProperty("degree");
            if (degree == null || degree.isBlank()) {
                throw new RuntimeException("degree is not set. Please run with -Ddegree=N");
            }

            MandelbrotCanvas.generate(
                    WIDTH,
                    HEIGHT,
                    CENTER_X,
                    CENTER_Y,
                    H_DIAMETER,
                    ITERATION_CAP,
                    BAILOUT_RADIUS_SQ,
                    Integer.parseInt(degree),
                    this.magnitudes,
                    this.escapes,
                    this.counters,
                    this.pixels);
            shuffle(this.pixels);
            int parallelism = Runtime.getRuntime().availableProcessors();
            this.parallelPipeline = Flux.fromArray(this.pixels)
                    .parallel(parallelism)
                    .runOn(Schedulers.parallel())
                    .doOnNext(frame -> execute(frame, blackhole))
                    .then();
            this.boundedElasticPipeline = Flux.fromArray(this.pixels)
                    .parallel(parallelism)
                    .runOn(Schedulers.boundedElastic())
                    .doOnNext(frame -> execute(frame, blackhole))
                    .then();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            this.counters.reset();
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void renderSchedulersParallel(Blackhole blackhole) {
            LOGGER.info(TOTAL_TASKS);

            this.parallelPipeline.block();
            MandelbrotCompletion.verify(this.counters, EXPECTED_OPERATIONS);
            blackhole.consume(this.escapes);
            blackhole.consume(this.magnitudes);
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void renderSchedulersBoundedElastic(Blackhole blackhole) {
            LOGGER.info(TOTAL_TASKS);

            this.boundedElasticPipeline.block();
            MandelbrotCompletion.verify(this.counters, EXPECTED_OPERATIONS);
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

        private final double[] magnitudes = new double[CANVAS * 4];
        private final int[] escapes = new int[CANVAS * 4];
        private final int sourceCount = Runtime.getRuntime().availableProcessors();
        private final EuhedralSubscriber[] subscribers = new EuhedralSubscriber[this.sourceCount];

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);

        private Flux<MandelbrotPixel>[] sources;

        private BufferedImage outputImage;
        private int[] rawImageBuffer;

        private int degree;
        private String outputDir;
        private String outputFileName;

        private ControlPlaneLattice controlPlane;

        private void makeSubscribers() {
            for (int i = 0; i < this.subscribers.length; i++) {
                this.subscribers[i] = new EuhedralSubscriber();
            }
        }

        @SuppressWarnings("unchecked")
        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            this.outputImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            this.rawImageBuffer = ((DataBufferInt) this.outputImage.getRaster().getDataBuffer()).getData();
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

            MandelbrotPixel[] pixels = new MandelbrotPixel[CANVAS];
            MandelbrotCanvas.generate(
                    WIDTH,
                    HEIGHT,
                    CENTER_X,
                    CENTER_Y,
                    H_DIAMETER,
                    ITERATION_CAP,
                    BAILOUT_RADIUS_SQ,
                    this.degree,
                    this.magnitudes,
                    this.escapes,
                    this.counters,
                    pixels);

            shuffle(pixels);
            this.sources = new Flux[this.sourceCount];
            int sourceSize = CANVAS / this.sourceCount;
            int remainder = CANVAS % this.sourceCount;
            int offset = 0;
            for (int i = 0; i < this.sourceCount; i++) {
                int length = sourceSize;
                if (i < remainder) {
                    length++;
                }
                MandelbrotPixel[] sourcePixels = new MandelbrotPixel[length];
                System.arraycopy(pixels, offset, sourcePixels, 0, length);
                this.sources[i] = Flux.fromArray(sourcePixels);
                offset += length;
            }
            makeSubscribers();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            this.counters.reset();
            makeSubscribers();
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void render(Blackhole blackhole) {
            LOGGER.info(TOTAL_TASKS);

            for (int i = 0; i < this.sourceCount; i++) {
                this.sources[i].subscribe(this.subscribers[i]);
            }
            for (EuhedralSubscriber subscriber : this.subscribers) {
                this.controlPlane.addUpstream(subscriber);
            }

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

            String histString = "Avg:   %.3f\nP0:    %d\nP50:   %d\nP90:   %d\nP99:   %d\nP99.9: %d\nP100:  %d\n\n";

            Histogram mHist = mag.getIntervalHistogram();
            String mString = String.format(
                    histString,
                    mHist.getMean(),
                    mHist.getValueAtPercentile(0),
                    mHist.getValueAtPercentile(50),
                    mHist.getValueAtPercentile(90),
                    mHist.getValueAtPercentile(99),
                    mHist.getValueAtPercentile(99.9),
                    mHist.getValueAtPercentile(100));
            LOGGER.info("\nMagnitude Histogram:\n{}", mString);

            Histogram eHist = escape.getIntervalHistogram();
            String eString = String.format(
                    histString,
                    eHist.getMean(),
                    eHist.getValueAtPercentile(0),
                    eHist.getValueAtPercentile(50),
                    eHist.getValueAtPercentile(90),
                    eHist.getValueAtPercentile(99),
                    eHist.getValueAtPercentile(99.9),
                    eHist.getValueAtPercentile(100));

            LOGGER.info("\nEscape Histogram:\n{}", eString);

            Path path = Paths.get("");
            if (this.outputFileName == null || this.outputFileName.isBlank()) {
                return;
            }
            if (this.outputDir != null && !this.outputDir.isBlank()) {
                path = path.resolve(this.outputDir);
                path.toFile().mkdirs();
            }
            path = path.resolve(this.outputFileName);

            LOGGER.info("Rendering final image");
            MandelbrotCanvas.render(rawImageBuffer, magnitudes, escapes, degree, ITERATION_CAP, BAILOUT_RADIUS_SQ);

            LOGGER.info("Exporting Mandelbrot d={} image to disk...", this.degree);
            ImageIO.write(this.outputImage, "png", path.toFile());
        }
    }
}
