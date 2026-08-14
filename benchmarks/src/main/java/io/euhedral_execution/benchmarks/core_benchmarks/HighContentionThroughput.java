package io.euhedral_execution.benchmarks.core_benchmarks;

import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.benchmarks.utils.NoOpExecutor;
import io.euhedral_execution.benchmarks.utils.RepeatingSink;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.control_plane.ControlPlaneShard;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hashing.HasherApi;
import java.time.Duration;
import java.util.BitSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class HighContentionThroughput {

    private static final Logger LOGGER = LoggerFactory.getLogger(HighContentionThroughput.class);
    private static final int PRODUCERS = SystemInfo.CPU_COUNT;
    private static final int TASKS = 32_000_000;
    private final PaddedLongAdder counters =
            new PaddedLongAdder(Runtime.getRuntime().availableProcessors(), true, true);
    private final RepeatingSink[] sinks = new RepeatingSink[PRODUCERS];
    private ControlPlaneLattice controlPlane;

    public HighContentionThroughput() {
        // Required for JMH
    }

    /// Waits until the monotonic completion count reaches `target` or fails at the timeout.
    static void await(PaddedLongAdder counters, long target, long timeoutNanos) {
        int spin = 0;
        long start = System.nanoTime();
        long log = start;
        long now = start;
        while (now - start < timeoutNanos) {
            if ((spin++ & 31) == 0) {
                long sum = counters.sum();
                if (now - log >= 3_000_000_000L) {
                    LOGGER.info("Progress: {}", sum);
                    log = now;
                }
                if (sum >= target) {
                    return;
                }
            }
            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
            now = System.nanoTime();
        }
        long observed = counters.sum();
        if (observed < target) {
            throw new IllegalStateException(
                    "Timed out waiting for completion target " + target + "; observed " + observed);
        }
    }

    /// Computes the next absolute completion target without allowing counter wraparound.
    static long completionTarget(long completed) {
        return Math.addExact(completed, TASKS);
    }

    /// Selects the highest active E-core, or the highest active core on homogeneous systems.
    static int selectHarnessCore(BitSet eCores, BitSet activeCores) {
        if (activeCores.cardinality() < 2) {
            return -1;
        }
        BitSet eligibleECores = (BitSet) eCores.clone();
        eligibleECores.and(activeCores);
        int selected = eligibleECores.previousSetBit(Math.max(0, eligibleECores.length() - 1));
        return selected >= 0 ? selected : activeCores.previousSetBit(activeCores.length() - 1);
    }

    /// Removes every logical CPU belonging to the reserved harness core.
    static BitSet workerCpuSet(BitSet activeCpus, BitSet harnessCoreCpus) {
        BitSet workers = (BitSet) activeCpus.clone();
        workers.andNot(harnessCoreCpus);
        if (workers.isEmpty()) {
            throw new IllegalStateException("Reserving the benchmark core left no CPUs for fragment workers");
        }
        return workers;
    }

    /// Builds the set of active physical-core IDs exposed by the topology model.
    private static BitSet activeCoreSet() {
        BitSet activeCores = new BitSet(SystemInfo.MAX_CORE_ID + 1);
        for (int core = 0; core <= SystemInfo.MAX_CORE_ID; core++) {
            if (SystemInfo.getCoreInfo(core) != null) {
                activeCores.set(core);
            }
        }
        return activeCores;
    }

    /// Pins the harness thread and returns the CPU set available to the lattice workers.
    private static BitSet isolateHarnessCore() {
        BitSet activeCpus = (BitSet) SystemInfo.getCpuSet().clone();
        int harnessCore = selectHarnessCore(SystemInfo.getECoreSet(), activeCoreSet());
        if (harnessCore < 0) {
            LOGGER.warn("Benchmark-core isolation is unavailable with fewer than two physical cores");
            return activeCpus;
        }

        BitSet harnessCpus = SystemInfo.getCoreInfo(harnessCore).getCpuSet();
        int harnessCpu = harnessCpus.nextSetBit(0);
        if (harnessCpu < 0 || !ThreadTools.setAffinity(harnessCpu)) {
            throw new IllegalStateException("Unable to pin benchmark thread to physical core " + harnessCore);
        }
        return workerCpuSet(activeCpus, harnessCpus);
    }

    /// Creates and starts one isolated lattice for this benchmark fork.
    @Setup(Level.Trial)
    public void setup(Blackhole blackhole) {
        long idHash = HasherApi.mix(HasherApi.BASE_SEED);

        for (int i = 0; i < sinks.length; i++) {
            this.sinks[i] = new RepeatingSink(NoOpFrame.generate(idHash, 2_048, this.counters));
        }

        BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
        LatticeConfig config = new LatticeConfig(
                LatticeConfig.DEFAULT_NAME,
                isolateHarnessCore(),
                Duration.ofMinutes(1),
                ControlPlaneShard.createBaseShard(LatticeConfig.DEFAULT_SHARD_NAME, base));
        this.controlPlane = ControlPlaneLattice.getOrCreate(config);
        this.controlPlane.start();
        for (var sink : this.sinks) {
            this.controlPlane.addUpstream(sink);
        }
    }

    /// Waits for exactly one invocation's additional operations using an absolute target.
    @Benchmark
    @OperationsPerInvocation(TASKS)
    public void ingest() {
        long target = completionTarget(this.counters.sum());
        await(this.counters, target, TimeUnit.MINUTES.toNanos(1));
    }

    /// Stops all repeating sources and releases the singleton lattice after the fork.
    @TearDown(Level.Trial)
    public void tearDown() {
        for (var sink : this.sinks) {
            sink.complete();
        }
        this.controlPlane.close();
    }
}
