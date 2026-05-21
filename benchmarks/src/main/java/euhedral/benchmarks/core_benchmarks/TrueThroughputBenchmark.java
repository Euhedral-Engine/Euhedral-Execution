package euhedral.benchmarks.core_benchmarks;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.benchmarks.pipelines.NoOpPipeline;
import euhedral.hashing.HasherApi;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.flow_control.ArrayIngestSink;
import euhedral.io.reactor.EuhedralSubscriber;
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

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class TrueThroughputBenchmark {
    private static final int BATCH = 32_000_000;

    private final PaddedLongAdder counters = new PaddedLongAdder(
            Runtime.getRuntime().availableProcessors(), true, true);
    private final NoOpFrame[][] frames = new NoOpFrame[32][];
    private final ArrayIngestSink[] sinks = new ArrayIngestSink[32];
    private ControlPlane controlPlane;

    private static void await(PaddedLongAdder counters) {
        int spin = 0;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);

        while (System.nanoTime() < deadline) {
            if ((spin++ & 31) == 0) {
                if (counters.sum() >= BATCH) {
                    break;
                }
            }
            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Setup(Level.Trial)
    public void setup(Blackhole blackhole) {
        long hash = ThreadLocalRandom.current().nextLong();
        hash = HasherApi.mix(hash);

        for(int i = 0; i < frames.length; i++) {
            this.frames[i] = NoOpFrame.generate(hash, 1_000_000, this.counters);
            for (NoOpFrame frame : this.frames[i]) {
                frame.randomizeHash(HasherApi.mix(hash++));
            }
            this.sinks[i] = new ArrayIngestSink(this.frames[i]);
        }

        DRRConfig drrConfig = DRRConfig.defaultConfig("ThroughputComparisonBenchmark", null);
        ExecutionManagerConfig emConfig = ExecutionManagerConfig.balancedDefault(null,
                "ThroughputComparisonBenchmark");
        this.controlPlane = ControlPlane.getOrCreate("ThroughputComparisonBenchmark",
                new NoOpPipeline("ThroughputComparisonBenchmark", drrConfig, emConfig, blackhole), null);
        this.controlPlane.start();
    }

    @Setup(Level.Invocation)
    public void setup() {
        this.counters.reset();
        for(var sink : this.sinks) {
            sink.reset();
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void ingest32million32sources() {
        for(var sink : this.sinks) {
            this.controlPlane.ingest(sink);
        }

        await(this.counters);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        this.controlPlane.close();
    }
}
