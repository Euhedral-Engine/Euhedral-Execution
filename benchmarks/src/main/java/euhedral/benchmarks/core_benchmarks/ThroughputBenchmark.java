package euhedral.benchmarks.core_benchmarks;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.benchmarks.pipelines.FramePublisher;
import euhedral.benchmarks.pipelines.NoOpPipeline;
import euhedral.hashing.HasherApi;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.control_plane.ControlPlane;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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
@Measurement(iterations = 5, time = 30, timeUnit = TimeUnit.SECONDS)
public class ThroughputBenchmark {

    private static final int BATCH = 32_000_000;

    private final PaddedLongAdder counters = new PaddedLongAdder(
            Runtime.getRuntime().availableProcessors(), true, true);
    public ExecutorService threadPool;
    public CyclicBarrier barrier = new CyclicBarrier(32 + 1);
    private FramePublisher[] publishers = new FramePublisher[32];
    private ControlPlane controlPlane;

    @Setup(Level.Trial)
    public void setup(Blackhole blackhole) {
        long hash = ThreadLocalRandom.current().nextLong();
        hash = HasherApi.mix(hash);

        for (int i = 0; i < this.publishers.length; i++) {
            this.publishers[i] = new FramePublisher(
                    NoOpFrame.generate(hash, 1_000_000, this.counters), 0, 1_000_000);
        }
        this.threadPool = Executors.newFixedThreadPool(this.publishers.length);

        DRRConfig drrConfig = DRRConfig.defaultConfig("ThroughputBenchmark", null);
        ExecutionManagerConfig emConfig = ExecutionManagerConfig.balancedDefault(null,
                "ThroughputBenchmark");
        this.controlPlane = ControlPlane.getOrCreate("ThroughputBenchmark",
                new NoOpPipeline("ThroughputBenchmark", drrConfig, emConfig, blackhole), null);
        this.controlPlane.start();
    }

    @Setup(Level.Invocation)
    public void reset() {
        counters.reset();
        barrier.reset();

        for (int i = 0; i < this.publishers.length; i++) {
            final int id = i;
            this.threadPool.submit(() -> {
                try {
                    this.publishers[id].reset();
                    this.barrier.await();
                    this.controlPlane.ingest(this.publishers[id]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void bench32Sources32MillionParallel() throws Exception {
        this.barrier.await();

        long sum = 0;
        int spin = 0;
        long log = System.nanoTime();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);

        long now;
        while ((now = System.nanoTime()) < deadline) {
            if ((spin++ & 31) == 0) {
                sum = this.counters.sum();
                if (sum >= 32_000_000) {
                    break;
                }
                if (now - log >= TimeUnit.SECONDS.toNanos(3)) {
                    log = now;
                }
            }
            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        if(now >= deadline) {
            System.out.println("Timeout! Total Completed: " + sum);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        this.controlPlane.close();
        this.threadPool.shutdownNow();
    }
}
