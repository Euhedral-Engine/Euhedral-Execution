package io.euhedral_execution.benchmarks.cfd.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicOutputTest {
    @TempDir
    Path directory;

    @Test
    void failedAndInterruptedPublicationKeepPriorFiles() throws Exception {
        Path target = directory.resolve("status.json");
        Files.writeString(target, "previous");
        assertThrows(
                IOException.class,
                () -> AtomicOutput.write(target, temporary -> {
                    Files.writeString(temporary, "partial");
                    throw new IOException("injected output failure");
                }));
        assertEquals("previous", Files.readString(target));
        try {
            assertThrows(
                    java.io.InterruptedIOException.class,
                    () -> AtomicOutput.write(target, temporary -> {
                        Files.writeString(temporary, "partial");
                        Thread.currentThread().interrupt();
                        AtomicOutput.checkInterrupted();
                    }));
        } finally {
            Thread.interrupted();
        }
        assertEquals("previous", Files.readString(target));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}
