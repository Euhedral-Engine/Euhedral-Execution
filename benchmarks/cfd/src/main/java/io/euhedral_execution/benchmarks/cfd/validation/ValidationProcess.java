package io.euhedral_execution.benchmarks.cfd.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Bounds solver and JMH process trees, including children whose controller exits unexpectedly.
public final class ValidationProcess {
    private ValidationProcess() {}

    public record Result(boolean completed, boolean timedOut, int exitCode) {}

    public static Result run(List<String> command, Path directory, Path log, long deadlineMillis) throws IOException {
        if (deadlineMillis <= 0 || deadlineMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("process deadline must be positive and fit nanoseconds");
        }
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        var children = new LinkedHashMap<Long, ProcessHandle>();
        long started = System.nanoTime();
        try {
            while (true) {
                process.descendants().forEach(child -> children.put(child.pid(), child));
                long remaining = deadlineMillis * 1_000_000 - (System.nanoTime() - started);
                if (remaining <= 0) {
                    return new Result(false, true, -1);
                }
                if (process.waitFor(Math.min(remaining, 100_000_000), TimeUnit.NANOSECONDS)) {
                    return new Result(process.exitValue() == 0, false, process.exitValue());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("child process interrupted", error);
        } finally {
            process.descendants().forEach(child -> children.put(child.pid(), child));
            for (var child : children.values()) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            boolean interrupted = Thread.interrupted();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
