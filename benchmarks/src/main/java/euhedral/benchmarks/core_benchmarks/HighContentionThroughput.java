package euhedral.benchmarks.core_benchmarks;

import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.benchmarks.utils.NoOpExecutor;
import euhedral.benchmarks.utils.RepeatingSink;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hashing.HasherApi;
import euhedral.io.config.LatticeConfig;
import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.impl.BaseCloneableObject;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
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

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class HighContentionThroughput {

    private static final int PRODUCERS = SystemInfo.CPU_COUNT;
    private static final int TASKS = 32_000_000;

    private static void await(PaddedLongAdder counters) {
        int spin = 0;
        long now;
        long log = System.nanoTime();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while ((now = System.nanoTime()) < deadline) {
            if ((spin++ & 31) == 0) {
                long sum = counters.sum();
                if (now - log >= 3_000_000_000L) {
                    System.out.println("Progress: " + sum);
                    log = now;
                }
                if (sum >= TASKS) {
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

    private final PaddedLongAdder counters =
            new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
    private final RepeatingSink[] sinks = new RepeatingSink[PRODUCERS];
    private ControlPlaneLattice controlPlane;

    @Setup(Level.Trial)
    public void setup(Blackhole blackhole) {
        long idHash = HasherApi.mix(HasherApi.BASE_SEED);

        for (int i = 0; i < sinks.length; i++) {
            this.sinks[i] = new RepeatingSink(NoOpFrame.generate(idHash, 250_000, this.counters));
        }

        BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
        LatticeConfig config = LatticeConfig.ofDefaults(base);
        this.controlPlane = ControlPlaneLattice.getOrCreate(config);
        this.controlPlane.start();
        for (var sink : this.sinks) {
            this.controlPlane.addUpstream(sink);
        }
    }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public void ingest() {
        this.counters.reset();
        await(this.counters);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        this.controlPlane.close();
    }
}
