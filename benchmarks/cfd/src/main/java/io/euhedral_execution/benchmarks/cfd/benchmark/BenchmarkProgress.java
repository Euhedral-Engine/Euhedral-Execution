package io.euhedral_execution.benchmarks.cfd.benchmark;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/// One retained counter per process. Only the reporter formats/prints while a timed pass is active.
final class BenchmarkProgress implements AutoCloseable {
    static final String PREFIX = "[CFD progress] ";
    static final long INTERVAL_MILLIS = 5_000;
    private final AtomicLong completed = new AtomicLong();
    private final String label;
    private final PrintStream out;
    private final LongSupplier clock;
    private final ScheduledExecutorService reporter;
    private String phase;
    private long total, started;
    private boolean active, closed;

    BenchmarkProgress(BenchmarkJob job) {
        this(
                Path.of(job.directory()).getParent().getFileName() + "/"
                        + Path.of(job.directory()).getFileName() + " ("
                        + job.options().backend() + ")",
                System.out,
                System::nanoTime,
                true);
    }

    BenchmarkProgress(String label, PrintStream out, LongSupplier clock, boolean periodic) {
        this(label, out, clock, periodic, INTERVAL_MILLIS);
    }

    BenchmarkProgress(String label, PrintStream out, LongSupplier clock, boolean periodic, long intervalMillis) {
        if (periodic && intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.label = label;
        this.out = out;
        this.clock = clock;
        reporter = periodic
                ? Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("cfd-progress").factory())
                : null;
        if (reporter != null) {
            reporter.scheduleWithFixedDelay(this::report, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    synchronized void begin(String phase, long steps) {
        if (closed || steps < 0) {
            throw new IllegalStateException("invalid progress phase");
        }
        this.phase = phase;
        total = steps;
        completed.setOpaque(0);
        started = clock.getAsLong();
        active = true;
        emit("running");
    }

    /// Driver-only telemetry; opaque access needs no publication of the simulation's fields.
    /// Phase transitions and snapshots share the monitor, so a reset cannot mix two pass identities.
    void completed(long step) {
        completed.setOpaque(step);
    }

    synchronized void report() {
        if (active && !closed) {
            emit("running");
        }
    }

    synchronized void finish() {
        if (active) {
            emit(total > 0 && completed.getOpaque() < total ? "stopped" : "complete");
            active = false;
        }
    }

    private void emit(String status) {
        long done = Math.min(total, Math.max(0, completed.getOpaque()));
        long elapsed = Math.max(0, clock.getAsLong() - started);
        String work = total == 0
                ? ""
                : String.format(Locale.ROOT, "step %d/%d (%.1f%%) | ", done, total, 100.0 * done / total);
        String eta = status.equals("complete")
                ? "00:00:00"
                : !status.equals("running") || total == 0
                        ? "unavailable"
                        : done == 0 || elapsed == 0 ? "estimating" : "~" + duration(etaSeconds(done, total, elapsed));
        out.println(PREFIX + label + " | " + phase + " | " + status + " | " + work + "elapsed "
                + duration(elapsed / 1_000_000_000) + " | ETA " + eta);
        out.flush();
    }

    static double etaSeconds(long completed, long total, long elapsedNanos) {
        if (completed <= 0 || total < completed || elapsedNanos <= 0) {
            return Double.NaN;
        }
        return (elapsedNanos / 1_000_000_000.0) * ((double) (total - completed) / completed);
    }

    private static String duration(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds >= Long.MAX_VALUE) {
            return "unavailable";
        }
        long value = (long) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (active) {
                emit("stopped");
                active = false;
            }
        }
        if (reporter != null) {
            reporter.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try {
                if (!reporter.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("CFD progress reporter did not stop");
                }
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
