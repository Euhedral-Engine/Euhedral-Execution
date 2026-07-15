package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import java.io.File;
import java.util.BitSet;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.FrameConsumerResultCallback;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ThreadToolsTest {
    @Test
    void testPinningToAll() {
        File testJar = new File("target/testing/test-container.jar");

        GenericContainer<?> container = new GenericContainer<>("eclipse-temurin:21-jre-alpine")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withCpusetCpus("0-" + (Runtime.getRuntime().availableProcessors() - 1))
                );
        container.addFileSystemBind(testJar.getAbsolutePath(), "/app/test-container.jar", BindMode.READ_ONLY);
        container.withCommand("sleep", "3600");
        container.start();

        BitSet expected = new BitSet();
        expected.set(0, Runtime.getRuntime().availableProcessors());

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

        validate(execOutput, expected.toString());
    }

    private void validate(StringBuffer execOutput, String expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!execOutput.toString().contains("Base Affinity Mask:")
                && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(50_000_000);
        }
        assertTrue(System.currentTimeMillis() < deadline, "Timeout waiting for pinning.");
        String[] out = execOutput.toString().split("Base Affinity Mask:\\s*");
        out = out[1].split("\n");

        assertTrue(out[0].startsWith(expected) || out[1].startsWith(expected), "Did not find base affinity in output.");
    }

    public static final class TestRunner {
        public static void main(String[] args) {
            ThreadTools.setTimerResolution(1);
            ThreadTools.releaseAffinity();
        }
    }
}