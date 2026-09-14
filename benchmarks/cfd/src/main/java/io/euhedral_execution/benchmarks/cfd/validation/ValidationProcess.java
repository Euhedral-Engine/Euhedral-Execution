package io.euhedral_execution.benchmarks.cfd.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ValidationProcess {
    private ValidationProcess() {}

    record Result(boolean completed, boolean timedOut, int exitCode) {}

    static Result run(List<String> command, Path directory, Path log, long deadlineMillis) throws IOException {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            boolean complete = process.waitFor(deadlineMillis, TimeUnit.MILLISECONDS);
            if (!complete) return new Result(false, true, -1);
            return new Result(process.exitValue() == 0, false, process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("validation process interrupted", e);
        } finally {
            /// Capture descendants before terminating the parent; do not leave solver children alive.
            if (process.isAlive()) {
                var children = process.descendants().toList();
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
