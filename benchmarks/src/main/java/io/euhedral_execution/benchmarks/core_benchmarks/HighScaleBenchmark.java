package io.euhedral_execution.benchmarks.core_benchmarks;

import io.euhedral_execution.benchmarks.frames.BenchArrayFrame;
import io.euhedral_execution.benchmarks.frames.MandelbulbFrame;
import io.euhedral_execution.benchmarks.utils.FractalExecutor;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.ingest.ArrayIngestSink;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hashing.HasherApi;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 60, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class HighScaleBenchmark {

    private static final Logger LOGGER = LoggerFactory.getLogger(HighScaleBenchmark.class);

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
            int j = (int) MathFunctions.unsignedMultiplyHigh(HasherApi.mix(seed++), i + 1L);
            MandelbulbFrame temp = pixels[i];
            temp.randomizeHash(seed++);
            pixels[j].randomizeHash(seed++);

            pixels[i] = pixels[j];
            pixels[j] = temp;
        }
    }

    private static void waitOnRender(PaddedLongAdder counters, long limit) {
        long sum = 0;
        int spin = 0;
        long log = System.nanoTime();

        long now;
        while (true) {
            now = System.nanoTime();
            if ((spin++ & 31) == 0) {
                sum = counters.sum();
                if (sum >= limit) {
                    break;
                }
                if (now - log >= TimeUnit.SECONDS.toNanos(3)) {
                    LOGGER.info("Progress: {}", sum);
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
            boolean socketLocal) throws Exception {
        int sockets = SystemInfo.SOCKET_COUNT;

        MandelbulbFrame[][][] pixels = new MandelbulbFrame[sockets][][];

        for (int i = 0; i < sockets; i++) {
            SocketInfo info = SystemInfo.getSocketInfo(i);
            if (info != null) {
                int cpu = info.getCpuSet().nextSetBit(0);
                PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(cpu,
                        HighScaleBenchmark.class.getSimpleName() + " Setup", Thread.MAX_PRIORITY,
                        true);

                final int socketId = i;
                executor.submit(() ->
                    pixels[socketId] =
                            MandelbulbFrame.generate(64, 1_500_625, 9_800, 9800, CENTER_X, CENTER_Y,
                                    0.0, MAX_RAY_STEPS, ITERATION_CAP, BAILOUT_RADIUS_SQ, blackhole,
                                    counters, socketLocal ? RoutingPolicy.SOCKET_LOCAL
                                            : RoutingPolicy.ANYWHERE)
                ).get();
                executor.shutdownNow();
            }
        }
        return pixels;
    }

    private HighScaleBenchmark() {

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
        private ControlPlaneLattice controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) throws Exception {
            FractalExecutor executor = new FractalExecutor(blackhole);
            BaseCloneableObject base = new BaseCloneableObject(executor);
            LatticeConfig config = LatticeConfig.ofDefaults(base);
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();

            LOGGER.info("Allocating...");
            MandelbulbFrame[][][] pixels = generate(blackhole, this.counters);

            long seed = SEED;

            LOGGER.info("Grouping and Shuffling...");
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
                        chunkArray[iter++] = new BenchArrayFrame(pixels[0][0][0].getIdHash(), set,
                                this.counters);

                        chunkArray[iter - 1].randomizeHash(seed++);
                        total -= Math.min(1024, total);
                    }

                    ArrayIngestSink sink = new ArrayIngestSink(chunkArray);
                    sinks[idx++] = sink;
                }
                LOGGER.info("Total tasks: {}", TASKS);
            }
        }

        @Setup(Level.Invocation)
        public void reset() {
            this.counters.reset();

            int idx = 0;
            for (ArrayIngestSink sink : sinks) {
                AbstractFrame[] array = sink.getFrameArray();
                this.sinks[idx++] = new ArrayIngestSink(array);
            }
        }

        @Benchmark
        public void render(OpCounter opCounter, InvocationCounter invocations) {
            for (ArrayIngestSink sink : this.sinks) {
                this.controlPlane.addUpstream(sink);
            }

            waitOnRender(this.counters, TASKS);
            opCounter.operations += TASKS;
            invocations.invocations++;
            invocations.measurements += TASKS;
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

        @State(Scope.Thread)
        @AuxCounters(Type.EVENTS)
        public static class InvocationCounter {

            public long invocations;
            public long measurements;
        }
    }

    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1, time = 30, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class OneByOne {

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[64 * SystemInfo.SOCKET_COUNT];
        private ControlPlaneLattice controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) throws Exception {
            FractalExecutor executor = new FractalExecutor(blackhole);
            BaseCloneableObject base = new BaseCloneableObject(executor);
            LatticeConfig config = LatticeConfig.ofDefaults(base);
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();

            LOGGER.info("Allocating...");
            MandelbulbFrame[][][] pixels = generate(blackhole, this.counters);

            int idx = 0;
            LOGGER.info("Shuffling...");
            for (var canvas : pixels) {
                for (var row : canvas) {
                    shuffle(row);
                    ArrayIngestSink sink = new ArrayIngestSink(row);
                    sinks[idx++] = sink;
                }
            }

            LOGGER.info("Total tasks: {}", TASKS);
        }

        @Setup(Level.Invocation)
        public void reset() {
            this.counters.reset();

            int idx = 0;
            for (ArrayIngestSink sink : sinks) {
                AbstractFrame[] array = sink.getFrameArray();
                this.sinks[idx++] = new ArrayIngestSink(array);
            }
        }

        @Benchmark
        public void render(OpCounter opCounter, InvocationCounter invocations) {
            for (ArrayIngestSink sink : this.sinks) {
                this.controlPlane.addUpstream(sink);
            }

            waitOnRender(this.counters, TASKS);
            opCounter.operations += TASKS;
            invocations.invocations++;
            invocations.measurements += TASKS;
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

        @State(Scope.Thread)
        @AuxCounters(Type.EVENTS)
        public static class InvocationCounter {

            public long invocations;
            public long measurements;
        }

    }
}
