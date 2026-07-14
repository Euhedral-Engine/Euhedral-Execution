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
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
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

public class LightContentionThroughput {

    private static final Logger LOGGER = LoggerFactory.getLogger(LightContentionThroughput.class);
    private static final int BATCH = 10_000_000;

    private static void await(PaddedLongAdder counters) {
        int spin = 0;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);

        while (System.nanoTime() < deadline) {
            if ((spin++ & 31) == 0 && counters.sum() >= BATCH) {
                    break;
                }

            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private LightContentionThroughput() {

    }

    @State(Scope.Benchmark)
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @Fork(1)
    public static class PCore {
        private final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors(), true, true);
        private final RepeatingSink[] sinks = new RepeatingSink[10];
        private ControlPlaneLattice controlPlane;
        private boolean skip;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            BitSet cores = (BitSet) SystemInfo.getPCoreSet().clone();
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

            int parts = BATCH / sinks.length;
            for(int i = 0; i < sinks.length; i++){
                sinks[i] = new RepeatingSink(NoOpFrame.generate(idHash, parts, counters));
            }

            LOGGER.info("Benchmark is using P cpus {}", cpus);
            BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
            LatticeConfig config = new LatticeConfig("LightContentionThroughputBenchmark", cpus,
                    Duration.ofSeconds(1), ControlPlaneShard.createBaseShard(base));
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();
            for(var sink : sinks) {
                this.controlPlane.addUpstream(sink);
            }
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void throughput() {
            if (skip) {
                return;
            }
            this.counters.reset();
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
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @Fork(1)
    public static class ECore {
        private final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors(), true, true);
        private final RepeatingSink[] sinks = new RepeatingSink[10];
        private ControlPlaneLattice controlPlane;
        private boolean skip;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            BitSet eCpus = (BitSet) SystemInfo.getECpuSet().clone();
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
            cpus.clear(cpus.nextSetBit(0) + 1, cpus.length());
            long idHash = HasherApi.mix(HasherApi.BASE_SEED);

            int parts = BATCH / sinks.length;
            for(int i = 0; i < sinks.length; i++){
                sinks[i] = new RepeatingSink(NoOpFrame.generate(idHash, parts, counters));
            }

            LOGGER.info("Benchmark is using E cpus {}", cpus);
            BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
            LatticeConfig config = new LatticeConfig("LightContentionThroughputBenchmark", cpus,
                    Duration.ofSeconds(1), ControlPlaneShard.createBaseShard(base));
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();
            for(var sink : sinks) {
                this.controlPlane.addUpstream(sink);
            }
        }

        @Benchmark
        @OperationsPerInvocation(BATCH)
        public void throughput() {
            if (skip) {
                return;
            }
            this.counters.reset();
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
