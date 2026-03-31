package euhedral.common.io.dispatch.benchmarks;

import euhedral.common.io.dispatch.utils.FlowRecorder;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@BenchmarkMode({Mode.SampleTime, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class FlowRecorderBenchmark {

    static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(FlowRecorderBenchmark.class.getSimpleName())
                .addProfiler("stack")
                .addProfiler("gc")
                .jvmArgs(args)
                .build();
        new Runner(opt).run();
    }

    FlowRecorder recorder;
    long[] random = new long[1_000_000];

    @Setup(Level.Trial)
    public void setupTrial() {
        recorder = new FlowRecorder();
        for(int i = 0; i < random.length; i++) {
            random[i] = ThreadLocalRandom.current().nextLong(1, 1000);
        }
    }

    @Benchmark
    @OperationsPerInvocation(1_000_000)
    public void benchmark(Blackhole bh) {
        for(int i = 0; i < 1_000_000; i++) {
            long now = System.nanoTime();
            recorder.record(now, random[i]);
            bh.consume(recorder.getThroughputNs());
            bh.consume(recorder.getEffectiveMeasurementWindowCount(now));
        }
    }
}
