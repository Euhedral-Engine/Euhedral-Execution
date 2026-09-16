package io.euhedral_execution.benchmarks.cfd.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkProgressTest {
    @TempDir
    Path directory;

    @Test
    void etaUsesCompletedStepsAndResetsBetweenPasses() {
        var clock = new AtomicLong();
        var bytes = new ByteArrayOutputStream();
        try (var progress = new BenchmarkProgress("fjp/fork-0", new PrintStream(bytes), clock::get, false)) {
            progress.begin("reference", 1000);
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("ETA estimating"));
            clock.set(1);
            progress.report();
            assertTrue(bytes.toString(StandardCharsets.UTF_8)
                    .lines()
                    .reduce((a, b) -> b)
                    .orElseThrow()
                    .contains("elapsed 00:00:00"));
            progress.completed(250);
            clock.set(2_000_000_000L);
            progress.report();
            assertTrue(bytes.toString(StandardCharsets.UTF_8)
                    .contains("step 250/1000 (25.0%) | elapsed 00:00:02 | ETA ~00:00:06"));
            progress.completed(500);
            clock.set(3_000_000_000L);
            progress.report();
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("ETA ~00:00:03"));
            progress.completed(1000);
            progress.finish();
            progress.begin("measurement", 10);
            String last = bytes.toString(StandardCharsets.UTF_8)
                    .lines()
                    .reduce((a, b) -> b)
                    .orElseThrow();
            assertTrue(last.contains("step 0/10 (0.0%) | elapsed 00:00:00 | ETA estimating"));
            progress.completed(10);
            progress.finish();
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("measurement | complete | step 10/10"));
        assertTrue(Double.isNaN(BenchmarkProgress.etaSeconds(0, 10, 1)));
        assertTrue(Double.isNaN(BenchmarkProgress.etaSeconds(11, 10, 1)));
        assertEquals(0, BenchmarkProgress.etaSeconds(10, 10, 1));
    }

    @Test
    void periodicReporterEmitsDuringWorkAndStopsOnClose() throws Exception {
        var observed = new java.util.concurrent.CountDownLatch(1);
        var reporterThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var clock = new AtomicLong();
        var output = new PrintStream(new ByteArrayOutputStream()) {
            @Override
            public void println(String text) {
                if (Thread.currentThread().getName().equals("cfd-progress") && text.contains("step 5/10")) {
                    reporterThread.set(Thread.currentThread());
                    observed.countDown();
                }
            }
        };
        try (var progress = new BenchmarkProgress("fjp", output, clock::get, true, 10)) {
            progress.begin("reference", 10);
            progress.completed(5);
            clock.set(2_000_000_000L);
            assertTrue(observed.await(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertNotNull(reporterThread.get());
        reporterThread.get().join(5_000);
        assertFalse(reporterThread.get().isAlive());
    }

    @Test
    void incompletePassDoesNotClaimCompletionOrAnEtaAfterStopping() {
        var bytes = new ByteArrayOutputStream();
        var progress = new BenchmarkProgress("static", new PrintStream(bytes), () -> 0, false);
        progress.begin("measurement", 100);
        progress.completed(20);
        progress.finish();
        progress.begin("checking fields", 0);
        progress.close();
        progress.close();
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("measurement | stopped | step 20/100"));
        assertTrue(output.contains("checking fields | stopped | elapsed 00:00:00 | ETA unavailable"));
        assertFalse(output.contains("| complete |"));
    }

    @Test
    void liveLogForwardingRetainsPartialLinesAndFlushesTheFinalProgress() throws Exception {
        var bytes = new ByteArrayOutputStream();
        Path log = directory.resolve("process.log");
        try (var forwarder = new ProgressLog(log, new PrintStream(bytes))) {
            forwarder.poll();
            Files.writeString(log, "JMH noise\n[CFD prog");
            forwarder.poll();
            assertEquals("", bytes.toString(StandardCharsets.UTF_8));
            Files.writeString(log, "ress] reference | step 5/10 | ETA ~00:00:01\n", StandardOpenOption.APPEND);
            forwarder.poll();
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("step 5/10"));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("JMH noise"));
            Files.writeString(
                    log,
                    "x".repeat(9000) + "\n# Iteration 1: [CFD progress] complete | ETA 00:00:00\n",
                    StandardOpenOption.APPEND);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, output.lines().count());
        assertTrue(output.contains("[CFD progress] complete | ETA 00:00:00"));
        assertFalse(output.contains("# Iteration"));
    }
}
