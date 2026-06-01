package euhedral.benchmarks.core_benchmarks;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.core_benchmarks.utils.MandelbrotCanvas;
import euhedral.benchmarks.frames.ArrayFrame;
import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.benchmarks.pipelines.FractalPipeline;
import euhedral.hashing.HasherApi;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.ControlPlaneConfig;
import euhedral.io.config.SchedulingConfig;
import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.reactor.common.EuhedralSubscriber;
import euhedral.io.utils.MathFunctions;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
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
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@BenchmarkMode({Mode.AverageTime})
public class BatchedMandelbrotBenchmark {

    // 8K Resolution 2X SSAA (7680 * 4320 * 4 = 132,710,400 distinct tasks)
    public static final int WIDTH = 7680;
    public static final int HEIGHT = 4320;
    public static final int CANVAS = WIDTH * HEIGHT;
    public static final int BATCH = 1024;
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
        if (sum < CANVAS) {
            throw new RuntimeException("Stall detected. Pending: " + (CANVAS - sum));
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
        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), false, true);
        private ArrayFrame[] frames;
        private Mono<ArrayFrame>[] monos;
        private BaseSubscriber<ArrayFrame> subscriber;

        private void makeSub(Blackhole blackhole) {
            this.subscriber = new BaseSubscriber<>() {
                @Override
                protected void hookOnNext(ArrayFrame frame) {
                    frame.execute();
                    frame.cpu = counters.fromRawIdx(Thread.currentThread().getId());
                    frame.doFinally();
                    blackhole.consume(frame);
                }
            };
        }

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            String degree = System.getProperty("degree");
            if (degree == null || degree.isBlank()) {
                throw new RuntimeException("degree is not set. Please run with -Ddegree=N");
            }

            MandelbrotPixel[] pixels = new MandelbrotPixel[CANVAS];
            MandelbrotCanvas.generate(WIDTH, HEIGHT, CENTER_X, CENTER_Y, H_DIAMETER,
                    ITERATION_CAP, BAILOUT_RADIUS_SQ, Integer.parseInt(degree), this.magnitudes,
                    this.escapes,
                    this.counters,
                    pixels);
            shuffle(pixels);

            MandelbrotPixel[][] pixelArray = new MandelbrotPixel[CANVAS / BATCH + (
                    CANVAS % BATCH > 0 ? 1 : 0)][];
            this.frames = new ArrayFrame[pixelArray.length];
            this.monos = new Mono[pixelArray.length];

            int idx = 0;
            int total = CANVAS;
            while (total > 0) {
                AbstractFrame[] set = new AbstractFrame[Math.min(BATCH, total)];
                System.arraycopy(pixels, idx * BATCH, set, 0, set.length);
                this.frames[idx] = new ArrayFrame(pixels[0].getIdHash(), set, this.counters);
                this.monos[idx] = Mono.just(this.frames[idx]);
                total -= Math.min(BATCH, total);
                idx++;
            }
            makeSub(blackhole);
        }

        @Setup(Level.Invocation)
        public void setupInvocation(Blackhole blackhole) {
            this.counters.reset();
            makeSub(blackhole);
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void renderSchedulersParallel(Blackhole blackhole) {
            Flux.fromArray(this.monos)
                    .flatMap(m -> m.subscribeOn(Schedulers.parallel()),
                            Runtime.getRuntime().availableProcessors())
                    .subscribe(this.subscriber);

            waitOnRender(this.counters);
            blackhole.consume(this.escapes);
            blackhole.consume(this.magnitudes);
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void renderSchedulersBoundedElastic(Blackhole blackhole) {
            Flux.fromArray(this.monos)
                    .flatMap(m -> m.subscribeOn(Schedulers.boundedElastic()),
                            Runtime.getRuntime().availableProcessors())
                    .subscribe(this.subscriber);

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

        private final double[] magnitudes = new double[CANVAS * 4];
        private final int[] escapes = new int[CANVAS * 4];

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);

        private ControlPlaneLattice controlPlane;
        private ArrayFrame[] frames;
        private EuhedralSubscriber subscriber;

        private void makeSub() {
            this.subscriber = new EuhedralSubscriber();
        }

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            String degree = System.getProperty("degree");
            if (degree == null || degree.isBlank()) {
                throw new RuntimeException("degree is not set. Please run with -Ddegree=N");
            }

            CacheConfig cacheConfig = CacheConfig.defaultConfig();
            SchedulingConfig schedConfig = SchedulingConfig.balancedDefault();
            FractalPipeline pipeline =
                    new FractalPipeline(cacheConfig, schedConfig, blackhole);
            ControlPlaneConfig config = new ControlPlaneConfig("MandelbrotBenchmark", null, null,
                    pipeline, null, null);
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();

            MandelbrotPixel[] pixels = new MandelbrotPixel[CANVAS];
            MandelbrotCanvas.generate(WIDTH, HEIGHT, CENTER_X, CENTER_Y, H_DIAMETER,
                    ITERATION_CAP, BAILOUT_RADIUS_SQ, Integer.parseInt(degree), this.magnitudes,
                    this.escapes,
                    this.counters,
                    pixels);
            shuffle(pixels);

            MandelbrotPixel[][] pixelArray = new MandelbrotPixel[CANVAS / BATCH + (
                    CANVAS % BATCH > 0 ? 1 : 0)][];
            this.frames = new ArrayFrame[pixelArray.length];

            long seed = SEED;

            int idx = 0;
            int total = CANVAS;
            while (total > 0) {
                AbstractFrame[] set = new AbstractFrame[Math.min(BATCH, total)];
                System.arraycopy(pixels, idx * BATCH, set, 0, set.length);
                this.frames[idx] = new ArrayFrame(pixels[0].getIdHash(), set, this.counters);
                this.frames[idx].randomizeHash(seed++);
                total -= Math.min(BATCH, total);
                idx++;
            }
            makeSub();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            this.counters.reset();
            makeSub();
        }

        @Benchmark
        @OperationsPerInvocation(CANVAS * 4)
        public void render(Blackhole blackhole) {
            Flux.fromArray(this.frames).subscribe(subscriber);
            this.controlPlane.addUpstream(this.subscriber);

            waitOnRender(this.counters);
            blackhole.consume(this.escapes);
            blackhole.consume(this.magnitudes);
        }

        @TearDown(Level.Trial)
        public void teardown() throws IOException {
            controlPlane.close();
        }
    }

}
