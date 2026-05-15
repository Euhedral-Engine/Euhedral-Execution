package euhedral.io.benchmarks;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import euhedral.io.DRRScheduler;
import euhedral.io.ExecutionManager;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.test_utils.TestFrame;
import euhedral.io.test_utils.TestPipeline;
import euhedral.io.test_utils.TestPipeline.TestExecutor;
import euhedral.io.test_utils.TestPublisher;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jctools.util.PaddedAtomicLong;
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
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class EndToEndBenchmark {

    private static final String RUNNER = "euhedral.io.benchmarks.EndToEndBenchmark$ControlPlaneBenchmark";

    @Test
    public void benchmark() throws Exception {

        File testJar = new File("target/test-jar-with-dependencies.jar");

        GenericContainer<?> container = new GenericContainer<>("eclipse-temurin:25-jre")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withCapAdd(Capability.SYS_ADMIN)
                        .withSecurityOpts(Collections.singletonList("seccomp=unconfined")));

        container.addFileSystemBind(testJar.getAbsolutePath(), "/app/test.jar", BindMode.READ_ONLY);

        container.addFileSystemBind(
                testJar.toPath().getParent().toAbsolutePath().resolve("results").toString(),
                "/opt/results", BindMode.READ_WRITE);
        container.withCommand("tail", "-f", "/dev/null");
        container.start();

        container.execInContainer("sh", "-c",
                "apk update && apk add libstdc++ g++ gcompat libc6-compat");
        ExecCreateCmdResponse execCreateCmdResponse = container.getDockerClient()
                .execCreateCmd(container.getContainerId()).withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("java", "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                        "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                        "--add-opens", "java.base/java.util=ALL-UNNAMED",
                        "-XX:-RestrictContended",
                        "-Dorg.slf4j.simpleLogger.defaultLogLevel=error",
                        "-cp", "/app/test.jar",
                        RUNNER).exec();

        container.getDockerClient().execStartCmd(execCreateCmdResponse.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                        System.out.print(new String(frame.getPayload()));
                        System.out.flush();
                    }
                }).awaitCompletion();
    }

    @BenchmarkMode({Mode.All})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 20, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class ControlPlaneBenchmark {

        static void main(String[] args) throws Exception {
            Options opt = new OptionsBuilder().include(
                            ControlPlaneBenchmark.class.getSimpleName())
                    .addProfiler("stack")
                    .addProfiler("gc")
                    .jvmArgs("-XX:-RestrictContended",
                            "--enable-native-access=ALL-UNNAMED",
                            "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                            "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                            "--add-opens", "java.base/java.util=ALL-UNNAMED",
                            "--enable-native-access=ALL-UNNAMED",
                            "-XX:-RestrictContended",
                            "-Djava.library.path=/app/lib/libasyncProfiler.so",
                            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error"
                    )
                    .resultFormat(ResultFormatType.JSON)
                    .result("/opt/results/e2e-benchmark-result.json")
                    .build();
            new Runner(opt).run();
        }

//        @Benchmark
//        @OperationsPerInvocation(8_000_000)
        public void benchOneProducerEightMillionOrdered(BenchmarkState state) throws Throwable {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch end = new CountDownLatch(1);
            PaddedAtomicLong countDown = new PaddedAtomicLong(8_000_000);

            state.producerPool.submit(() -> {
                TestPublisher subscription = new TestPublisher(state.orderedFramePool);
//                subscription.reset(end, countDown);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                state.controlPlane.ingest(subscription);
            });
            start.countDown();

            if (!end.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + countDown.get());
            }
        }

//        @Benchmark
//        @OperationsPerInvocation(32_000_000)
        public void benchOneProducer32MillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch end = new CountDownLatch(1);
            PaddedAtomicLong countDown = new PaddedAtomicLong(32_000_000);

            state.producerPool.submit(() -> {
                TestPublisher subscription = new TestPublisher(state.parallelFramePool);
//                subscription.reset(end, countDown);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                state.controlPlane.ingest(subscription);
            });
            start.countDown();

            if (!end.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + countDown.get());
            }
        }

        //        @Benchmark
//        @OperationsPerInvocation(8_000_000)
        public void benchEightProducersEightMillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch end = new CountDownLatch(1);
            PaddedAtomicLong countDown = new PaddedAtomicLong(8_000_000);

            for (int i = 0; i < 8; i++) {
                final int id = i;
                state.producerPool.submit(() -> {
                    try {
                        state.barrier8P.await();
//                        state.publishers[id].reset(end, countDown);
                        state.controlPlane.ingest(state.publishers[id]);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            state.barrier8P.await();

            if (!end.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + countDown.get());
            }
            state.barrier8P.reset();
        }

        @Benchmark
        @OperationsPerInvocation(32_000_000)
        public void bench32Producers32MillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch end = new CountDownLatch(1);
            PaddedAtomicLong countDown = new PaddedAtomicLong(32_000_000);

            for (int i = 0; i < 32; i++) {
                final int id = i;
                state.producerPool.submit(() -> {
                    try {
                        state.barrier32P.await();
//                        state.publishers[id].reset(end, countDown);
                        state.controlPlane.ingest(state.publishers[id]);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            state.barrier32P.await();

            if (!end.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + countDown.get());
            }

            state.barrier32P.reset();
        }

        @State(Scope.Benchmark)
        public static class BenchmarkState {

            public TestFrame[] orderedFramePool = TestFrame.generateOrdered(8_000_000);
            public TestFrame[] parallelFramePool = TestFrame.generateParallel(32_000_000);
            public TestPublisher[] publishers = new TestPublisher[32];
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
                        new DRRScheduler(drrConfig, null),
                        new ExecutionManager(dsmConfig),
                        new TestExecutor(null, bh));
                controlPlane = ControlPlane.getOrCreate("SystemTest", pipeline,
                        null);

                producerPool = Executors.newFixedThreadPool(32);
                for (int i = 0; i < publishers.length; i++) {
                    publishers[i] = new TestPublisher(TestFrame.generateParallel(1_000_000));
                }
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
