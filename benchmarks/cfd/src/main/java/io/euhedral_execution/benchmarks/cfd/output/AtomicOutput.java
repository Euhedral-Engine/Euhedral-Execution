package io.euhedral_execution.benchmarks.cfd.output;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Temporary files share the destination filesystem; publication requires an atomic rename.
final class AtomicOutput {
    private AtomicOutput() {}

    @FunctionalInterface
    interface Writer {
        void write(Path temporary) throws IOException;
    }

    static void write(Path target, Writer writer) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".cfd-", ".tmp");
        try {
            writer.write(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("interrupted during field output");
    }
}
