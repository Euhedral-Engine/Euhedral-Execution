package euhedral.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
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
class ResourceMonitorTest {

    private static GenericContainer<?> container;

    @BeforeAll
    public static void buildContainer() {

        File testJar = new File("target/test-container.jar");

        container = new GenericContainer<>("eclipse-temurin:21-jre-alpine")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withCpuQuota(150_000L) // 1.5 CPUs (Quota over 100k Period)
                        .withCpuPeriod(100_000L)
                        .withMemory(512 * 1024 * 1024L) // 512MB
                        .withCpusetCpus("0,1") // Pin to physical IDs 0 and 1
                );
        container.addFileSystemBind(testJar.getAbsolutePath(), "/app/test-container.jar",
                BindMode.READ_ONLY);
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
                .withCmd("java", "-jar", "/app/test-container.jar", TestRunner.class.getName())
                .exec();

        FrameConsumerResultCallback callback = new FrameConsumerResultCallback();
        callback.addConsumer(OutputType.STDOUT, frame -> {
            String s = frame.getUtf8String();
            execOutput.append(s);
            System.out.printf(s);
        });
        callback.addConsumer(OutputType.STDERR, frame -> {
            String s = frame.getUtf8String();
            System.err.printf(s);
        });
        container.getDockerClient().execStartCmd(execCreateCmdResponse.getId())
                .exec(callback);

        long start = System.currentTimeMillis();
        while (!execOutput.toString().contains("QUOTA=1.5")
                && (System.currentTimeMillis() - start) < 5000) {
            Thread.sleep(50);
        }
        assertTrue(execOutput.toString().contains("QUOTA=1.5"));
        System.out.println("\nDetected initial quota: 1.5");

        int markIndex = execOutput.length();

        container.getDockerClient().updateContainerCmd(containerId)
                .withCpuQuota(200_000L)
                .withCpuPeriod(100_000L)
                .exec();

        start = System.currentTimeMillis();
        while (!execOutput.substring(markIndex).contains("QUOTA=2.0")
                && (System.currentTimeMillis() - start) < 5000) {
            Thread.sleep(50);
        }

        assertTrue(execOutput.toString().contains("QUOTA=2.0"));
        System.out.println("\nDetected final quota: 2.0");
    }

    public static class TestRunner {

        public static void main(String[] args) {
            try {
                ResourceMonitor monitor = new ResourceMonitor(new TopologyMapper(),
                        Duration.ofMillis(50));
                monitor.start();

                for (int i = 0; i < 100; i++) {
                    HardwareUtilization utilization = monitor.getUtilization();
                    System.out.printf("SNAPSHOT:QUOTA=%.1f\n", utilization.quotaCpus());
                    System.out.flush();
                    Thread.sleep(200);
                }
            } catch (Throwable t) {
                System.err.println("Error: \n" + t);
            }
        }
    }
}