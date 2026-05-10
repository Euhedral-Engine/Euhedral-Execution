package euhedral.io.benchmarks;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.DRRScheduler;
import euhedral.io.ExecutionManager;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.impl.FrameManager;
import euhedral.io.test_utils.TestFrame;
import euhedral.io.test_utils.TestPipeline;
import euhedral.io.test_utils.TestPipeline.TestExecutor;
import euhedral.io.test_utils.TestPublisher;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

public class SteadyStateBenchmark {

    private static final String RUNNER = "euhedral.io.benchmarks.SteadyStateBenchmark$BenchmarkRunner";

    @Test
    public void benchmark() throws Exception {

        File testJar = new File("target/test-jar-with-dependencies.jar");

        GenericContainer<?> container = new GenericContainer<>("eclipse-temurin:25-jre")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withCapAdd(Capability.SYS_ADMIN)
                        .withSecurityOpts(Collections.singletonList("seccomp=unconfined")));

        container.withFileSystemBind(new File(
                        "src/test/resources/async-profiler-4.3-linux-x64/lib/libasyncProfiler.so").getAbsolutePath(),
                "/app/lib/libasyncProfiler.so", BindMode.READ_WRITE);
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
                        "--enable-native-access=ALL-UNNAMED",
                        "-XX:-RestrictContended",
                        "-Dorg.slf4j.simpleLogger.defaultLogLevel=error", "-cp", "/app/test.jar",
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

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        public final TestFrame[] parallelFramePool = TestFrame.generateParallel(64_000_000);
        public ExecutorService producerPool;
        private ControlPlane controlPlane = null;

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

            producerPool = Executors.newFixedThreadPool(1);
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() {
            try {
                controlPlane.close();
                PinnedThreadExecutor.closeAll();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @BenchmarkMode({Mode.All})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 1)
    @Measurement(iterations = 1)
    @Fork(value = 1)
    public static class BenchmarkRunner {

        static void main(String[] args) throws Exception {
            Options optSteadyState = new OptionsBuilder().include(
                            SteadyStateBenchmark.class.getSimpleName()).addProfiler("stack")
                    .addProfiler("gc")
                    .addProfiler(AsyncProfiler.class,
                            "libPath=/app/lib/libasyncProfiler.so;" +
                                    "event=itimer;" +
                                    "interval=100000;" +
                                    "alloc=64k;" +
                                    "output=jfr;" +
                                    "dir=/opt/results/async-profiler")
                    .jvmArgs("-XX:-RestrictContended",
                            "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                            "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                            "--enable-native-access=ALL-UNNAMED",
                            "-XX:-RestrictContended",
                            "-Djava.library.path=/app/lib/libasyncProfiler.so",
                            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error")
                    .resultFormat(ResultFormatType.JSON)
                    .result("/opt/results/steady-state-benchmark-result.json")
                    .build();
            new Runner(optSteadyState).run();
        }

        @Benchmark
        @OperationsPerInvocation(64_000_000)
        public void benchOneProducer64MillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch end = new CountDownLatch(1);
            PaddedAtomicLong countDown = new PaddedAtomicLong(64_000_000L);

            state.producerPool.submit(() -> {
                TestPublisher subscription = new TestPublisher(state.parallelFramePool);
                subscription.reset(end, countDown);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                state.controlPlane.ingest(subscription);
            });
            start.countDown();

            long startNs = System.nanoTime();
            if (!end.await(60 * 2, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + countDown.get());
            }

            int cycles = 0;
            FrameManager<Void, TestFrame> recycler = state.parallelFramePool[0].getRecycler();
            long deadline = startNs + TimeUnit.SECONDS.toNanos(60 * 2);
            while (countDown.get() > 0 && System.nanoTime() < deadline) {
                countDown.getAndUpdate(curr -> curr - recycler.dump(curr, TestFrame.PASSWORD));
                if (cycles++ < 128) {
                    Thread.onSpinWait();
                } else if (cycles < 512) {
                    Thread.yield();
                } else {
                    LockSupport.parkNanos(5_000);
                    cycles = 0;
                }
            }
        }

        /// This test is for profiling the idle state. It also helps the cpu cool down and prepare
        /// for the next iteration.
        @Benchmark
        public void profileIdleState() {
            Blackhole.consumeCPU(100);
            LockSupport.parkNanos(1_000_000_000);
        }
    }

}
