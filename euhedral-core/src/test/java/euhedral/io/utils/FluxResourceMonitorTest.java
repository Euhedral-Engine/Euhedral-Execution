package euhedral.io.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import java.io.File;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.FrameConsumerResultCallback;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class FluxResourceMonitorTest {

    private static final String RUNNER = "euhedral.io.utils.FluxResourceMonitorTest$TestRunner";

    private static GenericContainer<?> container;

    @BeforeAll
    public static void buildContainer() throws Exception {

        File testJar = new File("target/test-jar-with-dependencies.jar");

        container = new GenericContainer<>("eclipse-temurin:21-jre-alpine")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withCpuQuota(150_000L) // 1.5 CPUs (Quota over 100k Period)
                        .withCpuPeriod(100_000L)
                        .withMemory(512 * 1024 * 1024L) // 512MB
                        .withCpusetCpus("0,1") // Pin to physical IDs 0 and 1
                );
        container.addFileSystemBind(testJar.getAbsolutePath(), "/app/test.jar", BindMode.READ_ONLY);
        container.withCommand("sleep", "3600");
        container.start();
    }

    @Test
    public void testDynamicScaling() throws Exception {
        StringBuffer execOutput = new StringBuffer();
        String containerId = container.getContainerId();

        ExecCreateCmdResponse execCreateCmdResponse = container.getDockerClient()
                .execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("java", "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                        "-cp", "/app/test.jar", RUNNER)
                .exec();

        FrameConsumerResultCallback callback = new FrameConsumerResultCallback();
        callback.addConsumer(OutputType.STDOUT, frame -> {
            String s = frame.getUtf8String();
            execOutput.append(s);
        });
        container.getDockerClient().execStartCmd(execCreateCmdResponse.getId())
                .exec(callback);

        long start = System.currentTimeMillis();
        while (!execOutput.toString().contains("QUOTA=1.5")
                && (System.currentTimeMillis() - start) < 2000) {
            Thread.sleep(50);
        }
        assertTrue(execOutput.toString().contains("QUOTA=1.5"));
        System.out.println("\nDetected initial quota: 1.5");

        container.getDockerClient().updateContainerCmd(containerId)
                .withCpuQuota(200_000L)
                .withCpuPeriod(100_000L)
                .exec();

        start = System.currentTimeMillis();
        while (!execOutput.toString().contains("QUOTA=2.0")
                && (System.currentTimeMillis() - start) < 2000) {
            Thread.sleep(50);
        }

        assertTrue(execOutput.toString().contains("QUOTA=2.0"));
        System.out.println("\nDetected final quota: 2.0");
    }

    public static class TestRunner {

        static void main() throws InterruptedException {
            FluxResourceMonitor monitor = new FluxResourceMonitor(
                    Duration.ofMillis(50));

            for (int i = 0; i < 100; i++) {
                HardwareUtilization utilization = monitor.getUtilization();
                System.out.printf("SNAPSHOT:QUOTA=%.1f\n", utilization.quotaCpus());
                System.out.flush();
                Thread.sleep(200);
            }
        }
    }
}
