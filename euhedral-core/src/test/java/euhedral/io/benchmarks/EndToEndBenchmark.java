package euhedral.io.benchmarks;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import euhedral.atomics.PaddedLongAdder;
import euhedral.io.DRRCacheManager;
import euhedral.io.ExecutionManager;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.test_utils.TestFrame;
import euhedral.io.test_utils.TestOrigin;
import euhedral.io.test_utils.TestPipeline;
import euhedral.io.test_utils.TestPipeline.TestExecutor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class EndToEndBenchmark {

    private static final String RUNNER = "euhedral.io.benchmarks.EndToEndBenchmark$BenchmarkRunner";

    @Test // Use this if you want to run on linux or your OS doesn't allow the native calls
    public void benchmark() throws Exception {

        File testJar = new File("target/test-jar-with-dependencies.jar");

        GenericContainer<?> container =
                new GenericContainer<>("eclipse-temurin:25-jre").withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withPrivileged(true)
                                .withSecurityOpts(Collections.singletonList("seccomp=unconfined")));

        container.addFileSystemBind(testJar.getAbsolutePath(), "/app/test.jar", BindMode.READ_ONLY);

        container.addFileSystemBind(
                testJar.toPath().getParent().toAbsolutePath().resolve("results").toString(),
                "/opt/results", BindMode.READ_WRITE);
        container.withCommand("tail", "-f", "/dev/null");
        container.start();

        ExecCreateCmdResponse execCreateCmdResponse =
                container.getDockerClient().execCreateCmd(container.getContainerId())
                        .withAttachStdout(true).withAttachStderr(true)
                        .withCmd("java", "--add-exports",
                                "java.base/jdk.internal.platform=ALL-UNNAMED", "--add-exports",
                                "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                                "-XX:-RestrictContended",
                                "-Dorg.slf4j.simpleLogger.defaultLogLevel=error", "-cp",
                                "/app/test.jar", RUNNER).exec();

        container.getDockerClient().execStartCmd(execCreateCmdResponse.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                        System.out.print(new String(frame.getPayload()));
                        System.out.flush();
                    }
                }).awaitCompletion();
    }

    @BenchmarkMode({Mode.SampleTime, Mode.Throughput})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 20, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class BenchmarkRunner {

        private static final int M8 = 8_000_000;
        private static final int M32 = 32_000_000;

         public static void main(String[] args) throws Exception {
            ChainedOptionsBuilder opt = new OptionsBuilder().include(
                            EndToEndBenchmark.class.getSimpleName() + "."
                                    + BenchmarkRunner.class.getSimpleName())
//                    .addProfiler("gc")
//                    .addProfiler("perf", "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses")
                    .jvmArgs("-XX:+RestrictContended", "-XX:+UseThreadPriorities", "--enable-native-access=ALL-UNNAMED",
                            "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                            "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                            "--add-opens", "java.base/java.util=ALL-UNNAMED",
                            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");
            if (args.length > 0) {
                Path output = Paths.get(args[0]);
                Files.createDirectories(output);
                opt.resultFormat(ResultFormatType.JSON)
                        .result(output.resolve("e2e-benchmark-result.json").toAbsolutePath()
                                .toString());
            }

            new Runner(opt.build()).run();
        }

//        @Benchmark
//        @OperationsPerInvocation(M8)
        public void benchOneProducerEightMillionOrdered(BenchmarkState state) {
            state.counter.reset();
            CountDownLatch start = new CountDownLatch(1);

            state.producerPool.submit(() -> {
                TestOrigin origin = new TestOrigin(state.orderedFramePool);
                origin.reset(null, state.counter);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                state.controlPlane.ingest(origin);
            });
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
            long sum = sum(deadline, M8, state.counter);
            if (sum < M8) {
                throw new RuntimeException("Stall detected. Pending: " + (M8 - sum));
            }
        }

//        @Benchmark
//        @OperationsPerInvocation(M32)
        public void benchOneProducer32MillionParallel(BenchmarkState state) throws Throwable {
            state.counter.reset();
            CountDownLatch start = new CountDownLatch(1);

            state.producerPool.submit(() -> {
                TestOrigin origin = new TestOrigin(state.parallelFramePool);
                origin.reset(null, state.counter);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                state.controlPlane.ingest(origin);
            });
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long sum = sum(deadline, M32, state.counter);
            if (sum < M32) {
                throw new RuntimeException("Stall detected. Pending: " + (M32 - sum));
            }
        }

        @Benchmark
        @OperationsPerInvocation(M32)
        public void bench32Producers32MillionParallel(BenchmarkState state) throws Throwable {
            state.counter.reset();
            for (int i = 0; i < 32; i++) {
                final int id = i;
                state.producerPool.submit(() -> {
                    try {
                        state.barrier32P.await();
                        state.publishers[id].reset(null, state.counter);
                        state.controlPlane.ingest(state.publishers[id]);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            state.barrier32P.await();
            state.barrier32P.reset();

            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
            long sum = sum(deadline, M32, state.counter);
            if (sum < M32) {
                throw new RuntimeException("Stall detected. Pending: " + (M32 - sum));
            }
        }

        private static long sum(long deadline, int target, PaddedLongAdder counter) {
            long sum = 0;
            int spin = 0;
            while (System.nanoTime() < deadline) {
                if ((spin++ & 31) == 0) {
                    sum = counter.sum();
                    if (sum >= target) {
                        break;
                    }
                } else {
                    Thread.onSpinWait();
                }
            }
            return sum;
        }

        @State(Scope.Benchmark)
        public static class BenchmarkState {

            public PaddedLongAdder counter =
                    new PaddedLongAdder(32, true, true);
            public TestFrame[] orderedFramePool = TestFrame.generateOrdered(8_000_000);
            public TestFrame[] parallelFramePool = TestFrame.generateParallel(32_000_000);
            public TestOrigin[] publishers = new TestOrigin[32];
            public ExecutorService producerPool;
            public CyclicBarrier barrier8P = new CyclicBarrier(8 + 1);
            public CyclicBarrier barrier32P = new CyclicBarrier(32 + 1);
            private ControlPlane controlPlane;

            @Setup(Level.Trial)
            public void setupExecutor(Blackhole bh) {
                DRRConfig drrConfig = new DRRConfig(null, "SystemTest",
                        null);
                ExecutionManagerConfig dsmConfig = ExecutionManagerConfig.balancedDefault(null, "SystemTest");

                TestPipeline pipeline = new TestPipeline("SystemTest", null,
                        new DRRCacheManager(drrConfig),
                        new ExecutionManager(dsmConfig),
                        new TestExecutor(null, bh));
                controlPlane = ControlPlane.getOrCreate("SystemTest", pipeline,
                        null);

                producerPool = Executors.newFixedThreadPool(32);
                for (int i = 0; i < publishers.length; i++) {
                    publishers[i] = new TestOrigin(TestFrame.generateParallel(1_000_000));
                }
                LockSupport.parkNanos(500_000);
            }

            @TearDown(Level.Iteration)
            public void coolDown() throws Exception {
                Thread.sleep(1000);
            }

            @TearDown(Level.Trial)
            public void tearDownTrial() {
                try {
                    controlPlane.close();
                    producerPool.shutdownNow();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
