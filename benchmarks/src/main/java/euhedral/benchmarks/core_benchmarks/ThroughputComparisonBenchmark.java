package euhedral.benchmarks.core_benchmarks;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.benchmarks.pipelines.NoOpPipeline;
import euhedral.hashing.HasherApi;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.control_plane.ControlPlane;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import euhedral.io.flow_control.ArrayIngestSink;
import euhedral.io.reactor.EuhedralSubscriber;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
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
import reactor.core.scheduler.Schedulers;

@State(Scope.Benchmark)
public class ThroughputComparisonBenchmark {

    private static final int BATCH = 32_000_000;

    private static void await(PaddedLongAdder counters) {
        long sum = 0;
        int spin = 0;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);

        while (System.nanoTime() < deadline) {
            if ((spin++ & 31) == 0) {
                sum = counters.sum();
                if (sum >= BATCH) {
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

    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
    @Fork(1)
    public static class Reactor {

        private NoOpFrame[] frames;
        private BaseSubscriber<NoOpFrame> subscriber;

        private final PaddedLongAdder counters =
                new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), false, true);

        private void makeSub(Blackhole blackhole) {
            this.subscriber = new BaseSubscriber<>() {
                @Override
                protected void hookOnNext(NoOpFrame frame) {
                    frame.cpu = counters.fromRawIdx(Thread.currentThread().getId());
                    frame.doFinally();
                    blackhole.consume(frame);
                }
            };
        }

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            this.frames = NoOpFrame.generate(0, BATCH, this.counters);
            makeSub(blackhole);
        }

        @Setup(Level.Invocation)
        public void reset(Blackhole blackhole) {
            this.counters.reset();
            makeSub(blackhole);
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void ingestSchedulersParallel(Blackhole blackhole) {
            Flux.fromArray(this.frames)
                    .parallel()
                    .runOn(Schedulers.parallel())
                    .subscribe(this.subscriber);
            await(this.counters);
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void ingestSchedulersBoundedElastic(Blackhole blackhole) {
            Flux.fromArray(this.frames)
                    .parallel()
                    .runOn(Schedulers.boundedElastic())
                    .subscribe(this.subscriber);
            await(this.counters);
        }
    }

    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
    @Fork(1)
    public static class Euhedral {

        private final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors(), true, true);
        private final NoOpFrame[] frames = NoOpFrame.generate(ThreadLocalRandom.current().nextLong(), BATCH, this.counters);
        private EuhedralSubscriber subscriber;
        private ControlPlane controlPlane;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            long hash = ThreadLocalRandom.current().nextLong();
            hash = HasherApi.mix(hash);

            for (NoOpFrame frame : this.frames) {
                frame.randomizeHash(HasherApi.mix(hash++));
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
            this.subscriber = new EuhedralSubscriber();
            long hash = ThreadLocalRandom.current().nextLong();
            hash = HasherApi.mix(hash);

            for (NoOpFrame frame : this.frames) {
                frame.randomizeHash(HasherApi.mix(hash++));
            }
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void ingest() {
            Flux.fromArray(this.frames).subscribe(this.subscriber);
            this.controlPlane.ingest(this.subscriber);

            await(this.counters);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            this.controlPlane.close();
        }
    }
}
