package io.euhedral_execution.benchmarks.cfd.benchmark;

import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// Tail complete progress lines from the retained child log, including JMH's forwarded fork stdout.
final class ProgressLog implements AutoCloseable {
    private final Path log;
    private final PrintStream out;
    private final ScheduledExecutorService reader;
    private final byte[] buffer = new byte[8192];
    private final StringBuilder line = new StringBuilder();
    private RandomAccessFile input;
    private boolean oversized, failed;

    ProgressLog(Path log, PrintStream out) {
        this.log = log;
        this.out = out;
        reader = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("cfd-progress-log").factory());
        reader.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS);
    }

    synchronized void poll() {
        if (failed) {
            return;
        }
        try {
            if (input == null) {
                if (!Files.isRegularFile(log)) {
                    return;
                }
                input = new RandomAccessFile(log.toFile(), "r");
            }
            /// Bound each poll even if a failing child floods its log.
            for (int block = 0; block < 128; block++) {
                int size = input.read(buffer);
                if (size < 0) {
                    return;
                }
                accept(buffer, size);
            }
        } catch (IOException error) {
            failed = true;
            out.println(BenchmarkProgress.PREFIX + "terminal forwarding unavailable; see " + log + ": " + error);
        }
    }

    private void accept(byte[] bytes, int size) {
        for (int i = 0; i < size; i++) {
            int value = bytes[i] & 255;
            if (value == '\n') {
                if (!oversized) {
                    int prefix = line.indexOf(BenchmarkProgress.PREFIX);
                    if (prefix >= 0) {
                        out.println(line.substring(prefix).stripTrailing());
                        out.flush();
                    }
                }
                line.setLength(0);
                oversized = false;
            } else if (!oversized) {
                if (line.length() < 8192) {
                    line.append((char) value);
                } else {
                    oversized = true;
                    line.setLength(0);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        reader.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            if (!reader.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("CFD progress log reader did not stop");
            }
        } catch (InterruptedException error) {
            interrupted = true;
        } finally {
            try {
                synchronized (this) {
                    poll();
                    if (input != null) {
                        input.close();
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
