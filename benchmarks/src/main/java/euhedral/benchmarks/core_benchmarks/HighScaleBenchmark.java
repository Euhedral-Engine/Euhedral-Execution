package euhedral.benchmarks.core_benchmarks;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.frames.ArrayFrame;
import euhedral.benchmarks.frames.MandelbulbFrame;
import euhedral.benchmarks.pipelines.FractalPipeline;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.SocketInfo;
import euhedral.hashing.HasherApi;
import euhedral.io.config.ControlPlaneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.SchedulingConfig;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.ingest.ArrayIngestSink;
import euhedral.io.utils.MathFunctions;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.AuxCounters.Type;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 60, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class HighScaleBenchmark {

    public static final int MAX_RAY_STEPS = 200;
    public static final int ITERATION_CAP = 120;

    public static final double BAILOUT_RADIUS_SQ = 1_000_000.0;

    private static final long SEED = HasherApi.BASE_SEED;

    private static final double CENTER_X = 0;
    private static final double CENTER_Y = 0;

    private static final long TASKS = 9_800L * 9_800L * 4 * SystemInfo.SOCKET_COUNT;

    private static void shuffle(MandelbulbFrame[] pixels) {
        long seed = SEED;
        for (int i = pixels.length - 1; i > 0; i--) {
            int j = (int) MathFunctions.unsignedMultiplyHigh(HasherApi.mix(seed++), i + 1);
            MandelbulbFrame temp = pixels[i];
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

        long now;
        while (true) {
            now = System.nanoTime();
            if ((spin++ & 31) == 0) {
                sum = counters.sum();
                if (sum >= TASKS) {
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

    private static MandelbulbFrame[][][] generate(Blackhole blackhole, PaddedLongAdder counters)
            throws Exception {
        return generate(blackhole, counters, false);
    }

    private static MandelbulbFrame[][][] generate(Blackhole blackhole, PaddedLongAdder counters,
            boolean socketLocal)
            throws Exception {
        int sockets = SystemInfo.SOCKET_COUNT;

        MandelbulbFrame[][][] pixels = new MandelbulbFrame[sockets][][];

        for (int i = 0; i < sockets; i++) {
            SocketInfo info = SystemInfo.getSocketInfo(i);
            if (info != null) {
                int cpu = info.getCpuSet().nextSetBit(0);
                PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(cpu,
                        HighScaleBenchmark.class.getSimpleName() + " Setup",
                        Thread.MAX_PRIORITY,
                        true);

                final int socketId = i;
                executor.submit(() -> {
                    pixels[socketId] = MandelbulbFrame.generate(64, 1_500_625, 9_800,
                            9800,
                            CENTER_X, CENTER_Y, 0.0, MAX_RAY_STEPS, ITERATION_CAP,
                            BAILOUT_RADIUS_SQ,
                            blackhole, counters,
                            socketLocal ? RoutingPolicy.SOCKET_LOCAL : RoutingPolicy.ANY);
                }).get();
                executor.shutdownNow();
            }
        }
        return pixels;
    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 1, time = 60, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class Batched {

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[64 * SystemInfo.SOCKET_COUNT];
        private ControlPlane controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) throws Exception {
            MandelbulbFrame[][][] pixels = generate(blackhole, this.counters);

            long seed = SEED;

            int idx = 0;
            // Go through each image
            for (var canvas : pixels) {
                // Go through each row in the image
                for (var row : canvas) {
                    shuffle(row);

                    int chunks = row.length / 1024;
                    chunks += (row.length % 1024) == 0 ? 0 : 1;
                    AbstractFrame[] chunkArray = new AbstractFrame[chunks];

                    int iter = 0;
                    int total = row.length;
                    // Chunk the row into 1024 sized chunks
                    while (total > 0) {
                        AbstractFrame[] set = new AbstractFrame[Math.min(1024, total)];
                        System.arraycopy(row, iter * 1024, set, 0, set.length);
                        chunkArray[iter++] = new ArrayFrame(pixels[0][0][0].getIdHash(), set,
                                this.counters);

                        chunkArray[iter - 1].randomizeHash(seed++);
                        total -= Math.min(1024, total);
                    }

                    ArrayIngestSink sink = new ArrayIngestSink(chunkArray);
                    sinks[idx++] = sink;
                }
            }

            DRRConfig drrConfig = DRRConfig.defaultConfig();
            SchedulingConfig schedConfig = SchedulingConfig.balancedDefault();
            FractalPipeline pipeline =
                    new FractalPipeline(drrConfig, schedConfig, blackhole);
            ControlPlaneConfig config = new ControlPlaneConfig("HighScaleBenchmark", null, null,
                    pipeline, null, null);
            this.controlPlane = ControlPlane.getOrCreate(config);
            this.controlPlane.start();
        }

        @Setup(Level.Invocation)
        public void reset() {
            this.counters.reset();

            for (ArrayIngestSink sink : sinks) {
                sink.reset();
            }
        }

        @Benchmark
        public void render(OpCounter opCounter) {
            for (ArrayIngestSink sink : this.sinks) {
                this.controlPlane.ingest(sink);
            }

            waitOnRender(this.counters);
            opCounter.operations = TASKS;
        }

        @TearDown(Level.Trial)
        public void shutdown() {
            this.controlPlane.close();
        }

        @State(Scope.Thread)
        @AuxCounters(Type.OPERATIONS)
        public static class OpCounter {

            public long operations;
        }
    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 1, time = 60, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class BatchedSocketLocal {

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[64 * SystemInfo.SOCKET_COUNT];
        private ControlPlane controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) throws Exception {
            MandelbulbFrame[][][] pixels = generate(blackhole, this.counters, true);

            long seed = SEED;

            int idx = 0;
            // Go through each image
            for (var canvas : pixels) {
                // Go through each row in the image
                for (var row : canvas) {
                    shuffle(row);

                    int chunks = row.length / 1024;
                    chunks += (row.length % 1024) == 0 ? 0 : 1;
                    AbstractFrame[] chunkArray = new AbstractFrame[chunks];

                    int iter = 0;
                    int total = row.length;
                    // Chunk the row into 1024 sized chunks
                    while (total > 0) {
                        AbstractFrame[] set = new AbstractFrame[Math.min(1024, total)];
                        System.arraycopy(row, iter * 1024, set, 0, set.length);
                        chunkArray[iter++] = new ArrayFrame(pixels[0][0][0].getIdHash(), set,
                                this.counters);

                        chunkArray[iter - 1].randomizeHash(seed++);
                        total -= Math.min(1024, total);
                    }

                    ArrayIngestSink sink = new ArrayIngestSink(chunkArray);
                    sinks[idx++] = sink;
                }
            }

            DRRConfig drrConfig = DRRConfig.defaultConfig();
            SchedulingConfig schedConfig = SchedulingConfig.balancedDefault();
            FractalPipeline pipeline =
                    new FractalPipeline(drrConfig, schedConfig, blackhole);
            ControlPlaneConfig config = new ControlPlaneConfig("HighScaleBenchmark", null, null,
                    pipeline, null, null);
            this.controlPlane = ControlPlane.getOrCreate(config);
            this.controlPlane.start();
        }

        @Setup(Level.Invocation)
        public void reset() {
            this.counters.reset();

            for (ArrayIngestSink sink : sinks) {
                sink.reset();
            }
        }

        @Benchmark
        public void render(OpCounter opCounter) {
            for (ArrayIngestSink sink : this.sinks) {
                this.controlPlane.ingest(sink);
            }

            waitOnRender(this.counters);
            opCounter.operations = TASKS;
        }

        @TearDown(Level.Trial)
        public void shutdown() {
            this.controlPlane.close();
        }

        @State(Scope.Thread)
        @AuxCounters(Type.OPERATIONS)
        public static class OpCounter {

            public long operations;
        }
    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 1, time = 60, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class OneByOne {

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[64 * SystemInfo.SOCKET_COUNT];
        private ControlPlane controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) throws Exception {
            MandelbulbFrame[][][] pixels = generate(blackhole, this.counters);

            int idx = 0;
            for (var canvas : pixels) {
                for (var row : canvas) {
                    shuffle(row);
                    ArrayIngestSink sink = new ArrayIngestSink(row);
                    sinks[idx++] = sink;
                }
            }

            DRRConfig drrConfig = DRRConfig.defaultConfig();
            SchedulingConfig schedConfig = SchedulingConfig.balancedDefault();
            FractalPipeline pipeline =
                    new FractalPipeline(drrConfig, schedConfig, blackhole);
            ControlPlaneConfig config = new ControlPlaneConfig("HighScaleBenchmark", null, null,
                    pipeline, null, null);
            this.controlPlane = ControlPlane.getOrCreate(config);
            this.controlPlane.start();
        }

        @Setup(Level.Invocation)
        public void reset() {
            this.counters.reset();

            for (ArrayIngestSink sink : sinks) {
                sink.reset();
            }
        }

        @Benchmark
        public void render(OpCounter opCounter) {
            for (ArrayIngestSink sink : this.sinks) {
                this.controlPlane.ingest(sink);
            }

            waitOnRender(this.counters);
            opCounter.operations = TASKS;
        }

        @TearDown(Level.Trial)
        public void shutdown() {
            this.controlPlane.close();
        }

        @State(Scope.Thread)
        @AuxCounters(Type.OPERATIONS)
        public static class OpCounter {

            public long operations;
        }

    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 1, time = 60, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class OneByOneSocketLocal {

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[64 * SystemInfo.SOCKET_COUNT];
        private ControlPlane controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) throws Exception {
            MandelbulbFrame[][][] pixels = generate(blackhole, this.counters, true);

            int idx = 0;
            for (var canvas : pixels) {
                for (var row : canvas) {
                    shuffle(row);
                    ArrayIngestSink sink = new ArrayIngestSink(row);
                    sinks[idx++] = sink;
                }
            }

            DRRConfig drrConfig = DRRConfig.defaultConfig();
            SchedulingConfig schedConfig = SchedulingConfig.balancedDefault();
            FractalPipeline pipeline =
                    new FractalPipeline(drrConfig, schedConfig, blackhole);
            ControlPlaneConfig config = new ControlPlaneConfig("HighScaleBenchmark", null, null,
                    pipeline, null, null);
            this.controlPlane = ControlPlane.getOrCreate(config);
            this.controlPlane.start();
        }

        @Setup(Level.Invocation)
        public void reset() {
            this.counters.reset();

            for (ArrayIngestSink sink : sinks) {
                sink.reset();
            }
        }

        @Benchmark
        public void render(OpCounter opCounter) {
            for (ArrayIngestSink sink : this.sinks) {
                this.controlPlane.ingest(sink);
            }

            waitOnRender(this.counters);
            opCounter.operations = TASKS;
        }

        @TearDown(Level.Trial)
        public void shutdown() {
            this.controlPlane.close();
        }

        @State(Scope.Thread)
        @AuxCounters(Type.OPERATIONS)
        public static class OpCounter {

            public long operations;
        }

    }
}
