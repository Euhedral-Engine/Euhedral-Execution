package io.euhedral_execution.benchmarks.cfd.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ProcessDeadlineIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void deadlineStopsTheControllerAndItsFork() throws Exception {
        Path marker = directory.resolve("child.pid");
        var result = ValidationProcess.run(
                List.of(
                        java(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Blocker.class.getName(),
                        "controller",
                        marker.toString()),
                directory,
                directory.resolve("process.log"),
                5000);
        assertTrue(result.timedOut());
        assertFalse(result.completed());
        assertTrue(Files.isRegularFile(marker));
        long pid = Long.parseLong(Files.readString(marker));
        var child = ProcessHandle.of(pid);
        if (child.isPresent()) {
            child.get().onExit().get(5, TimeUnit.SECONDS);
            assertFalse(child.get().isAlive());
        }
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    public static class Blocker {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("controller")) {
                var child = new ProcessBuilder(
                                java(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Blocker.class.getName(),
                                "child",
                                args[1])
                        .inheritIO()
                        .start();
                child.waitFor();
            } else {
                Files.writeString(
                        Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                new CountDownLatch(1).await();
            }
        }
    }
}
