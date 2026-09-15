package io.euhedral_execution.benchmarks.core_benchmarks;

import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

final class MandelbrotCompletion {

    private MandelbrotCompletion() {}

    static void await(PaddedLongAdder counters, long expected, long timeoutNanos, Logger logger) {
        int spin = 0;
        long log = System.nanoTime();
        long deadline = System.nanoTime() + timeoutNanos;

        long now;
        while ((now = System.nanoTime()) < deadline) {
            if ((spin++ & 31) == 0) {
                long completed = counters.sum();
                if (completed == expected) {
                    return;
                }
                if (completed > expected) {
                    throw unexpectedCount(completed, expected);
                }
                if (now - log >= TimeUnit.SECONDS.toNanos(3)) {
                    logger.info("Progress: {}", completed);
                    log = now;
                }
            }
            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        verify(counters, expected);
    }

    static void verify(PaddedLongAdder counters, long expected) {
        long completed = counters.sum();
        if (completed != expected) {
            throw unexpectedCount(completed, expected);
        }
    }

    private static IllegalStateException unexpectedCount(long completed, long expected) {
        return new IllegalStateException("Completed " + completed + " of " + expected + " operations");
    }
}
