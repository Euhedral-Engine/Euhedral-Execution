package euhedral.benchmarks.core_benchmarks;

import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.benchmarks.pipelines.NoOpExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CoreInfo;
import euhedral.hardware_utils.SystemInfo.CpuCacheLayout;
import euhedral.hashing.HasherApi;
import euhedral.io.config.ControlPlaneConfig;
import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.impl.BaseCloneableObject;
import euhedral.io.ingest.ArrayIngestSink;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
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

public class LightContentionThroughput {

    private static final int BATCH = 10_000_000;

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

    @State(Scope.Benchmark)
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
    @Fork(1)
    public static class PCore {
        private final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors(), true, true);
        private final NoOpFrame[][] frames = new NoOpFrame[10][];
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[10];
        private ControlPlaneLattice controlPlane;
        private boolean skip;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            BitSet cores = (BitSet) SystemInfo.get_P_CoreSet().clone();
            if (cores.cardinality() == 0) {
                skip = true;
                return;
            }

            int c = cores.nextSetBit(1);
            CoreInfo info = SystemInfo.getCoreInfo(c);
            BitSet cpus = info.getCpuSet();

            c = cores.nextSetBit(c + 1);
            cpus.or(SystemInfo.getCoreInfo(c).getCpuSet());

            long idHash = HasherApi.mix(HasherApi.BASE_SEED);

            long seed = HasherApi.BASE_SEED;
            int parts = BATCH / sinks.length;
            for(int i = 0; i < sinks.length; i++){
                frames[i] = NoOpFrame.generate(idHash, parts, counters);
                for(var frame : frames[i]){
                    frame.randomizeHash(seed++);
                }
                sinks[i] = new ArrayIngestSink(frames[i]);
            }

            System.out.println("Benchmark is using P cpus " + cpus);
            BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
            ControlPlaneConfig config = new ControlPlaneConfig("LightContentionThroughputBenchmark", cpus,
                    null, base, null, null);
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();
        }

        @Setup(Level.Invocation)
        public void iterSetup() {
            if (skip) {
                return;
            }
            long seed = HasherApi.BASE_SEED;
            for(int i = 0; i < sinks.length; i++) {
                for(var frame : frames[i]) {
                    frame.randomizeHash(seed++);
                }
                sinks[i] = new ArrayIngestSink(frames[i]);
            }
            this.counters.reset();
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void throughput() {
            if (skip) {
                return;
            }

            for(var sink : sinks) {
                this.controlPlane.addUpstream(sink);
            }
            await(this.counters);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (skip) {
                return;
            }
            this.controlPlane.close();
        }
    }

    @State(Scope.Benchmark)
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
    @Fork(1)
    public static class ECore {
        private final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors(), true, true);
        private final NoOpFrame[][] frames = new NoOpFrame[10][];
        private final ArrayIngestSink[] sinks = new ArrayIngestSink[10];
        private ControlPlaneLattice controlPlane;
        private boolean skip;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            BitSet eCpus = (BitSet) SystemInfo.get_E_CpuSet().clone();
            BitSet cpus = eCpus;
            if (cpus.cardinality() == 0) {
                skip = true;
                return;
            }
            for (int i = eCpus.nextSetBit(0); i >= 0; i = eCpus.nextSetBit(i + 1)) {
                CpuCacheLayout layout = SystemInfo.getCacheLayout(i);
                BitSet l2 = layout.getL2Mask();
                boolean usable = true;
                for (int j = l2.nextSetBit(0); j >= 0; j = l2.nextSetBit(j + 1)) {
                    if (SystemInfo.getCpuInfo(j).core() == 0) {
                        usable = false;
                        break;
                    }
                }
                if (usable) {
                    cpus = l2;
                    break;
                }
            }

            long idHash = HasherApi.mix(HasherApi.BASE_SEED);

            long seed = HasherApi.BASE_SEED;
            int parts = BATCH / sinks.length;
            for(int i = 0; i < sinks.length; i++){
                frames[i] = NoOpFrame.generate(idHash, parts, counters);
                for(var frame : frames[i]){
                    frame.randomizeHash(seed++);
                }
                sinks[i] = new ArrayIngestSink(frames[i]);
            }

            System.out.println("Benchmark is using E cpus " + cpus);
            BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
            ControlPlaneConfig config = new ControlPlaneConfig("LightContentionThroughputBenchmark", cpus,
                    null, base, null, null);
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();
        }

        @Setup(Level.Invocation)
        public void iterSetup() {
            if (skip) {
                return;
            }

            long seed = HasherApi.BASE_SEED;
            for(int i = 0; i < sinks.length; i++) {
                for(var frame : frames[i]) {
                    frame.randomizeHash(seed++);
                }
                sinks[i] = new ArrayIngestSink(frames[i]);
            }
            this.counters.reset();
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void throughput() {
            if (skip) {
                return;
            }

            for(var sink : sinks) {
                this.controlPlane.addUpstream(sink);
            }
            await(this.counters);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (skip) {
                return;
            }
            this.controlPlane.close();
        }
    }
}
