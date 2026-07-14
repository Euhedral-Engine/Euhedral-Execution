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
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
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

@State(Scope.Benchmark)
public class EndToEndLatencyBenchmark {

    private static final Logger LOGGER = LoggerFactory.getLogger(EndToEndLatencyBenchmark.class);
    private static final int BATCH_SIZE = 100_000;

    private static void await(PaddedLongAdder counters) {
        int spin = 0;
        long sum;

        long start = System.nanoTime();
        long timeoutNanos = TimeUnit.SECONDS.toNanos(5);

        while (true) {
            sum = counters.sum();
            if (sum >= BATCH_SIZE) {
                break;
            }

            if ((spin++ & 1023) == 0) {
                if ((System.nanoTime() - start) > timeoutNanos) {
                    LOGGER.error("Timeout! Target: {}, Processed: {}", BATCH_SIZE, sum);
                    break;
                }
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private EndToEndLatencyBenchmark() {

    }

    @State(Scope.Benchmark)
    @BenchmarkMode({Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @Fork(3)
    public static class PCore {

        final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors());
        RepeatingSink ingestSink;
        private ControlPlaneLattice controlPlane;
        private boolean skip = false;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            BitSet cores = (BitSet) SystemInfo.getPCoreSet().clone();
            if (cores.cardinality() == 0) {
                skip = true;
                return;
            }

            long idHash = ThreadLocalRandom.current().nextLong();
            this.ingestSink = new RepeatingSink(NoOpFrame.generate(idHash, BATCH_SIZE, counters));

            int i = cores.nextSetBit(1);
            CoreInfo info = SystemInfo.getCoreInfo(i);
            BitSet cpus = info.getCpuSet();

            i = cores.nextSetBit(i + 1);
            cpus.or(SystemInfo.getCoreInfo(i).getCpuSet());

            LOGGER.info("Benchmark is using P cpus {}", cpus);
            BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
            LatticeConfig config = new LatticeConfig("EndToEndLatencyBenchmark", cpus,
                    Duration.ofSeconds(1), ControlPlaneShard.createBaseShard(base));
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();
            this.controlPlane.addUpstream(this.ingestSink);
        }

        @Benchmark
        @OperationsPerInvocation(BATCH_SIZE)
        public void endToEnd() {
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
    @BenchmarkMode({Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @Fork(3)
    public static class ECore {

        final PaddedLongAdder counters = new PaddedLongAdder(
                Runtime.getRuntime().availableProcessors());
        RepeatingSink ingestSink;
        private ControlPlaneLattice controlPlane;
        private boolean skip = false;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            BitSet eCpus = (BitSet) SystemInfo.getECpuSet().clone();
            BitSet cpus = eCpus;
            if (cpus.cardinality() == 0) {
                skip = true;
                return;
            }

            long idHash = ThreadLocalRandom.current().nextLong();
            this.ingestSink = new RepeatingSink(NoOpFrame.generate(idHash, BATCH_SIZE, counters));

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

            LOGGER.info("Benchmark is using E cpus {}", cpus);
            BaseCloneableObject base = new BaseCloneableObject(new NoOpExecutor(blackhole));
            LatticeConfig config = new LatticeConfig("EndToEndLatencyBenchmark", cpus,
                    Duration.ofSeconds(1), ControlPlaneShard.createBaseShard(base));
            this.controlPlane = ControlPlaneLattice.getOrCreate(config);
            this.controlPlane.start();
            this.controlPlane.addUpstream(this.ingestSink);
        }

        @Benchmark
        @OperationsPerInvocation(BATCH_SIZE)
        public void endToEnd() {
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

