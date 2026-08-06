package io.euhedral_execution.hardware_utils.internal.monitor;

import java.util.concurrent.locks.LockSupport;

public interface DeadlineWaiter {

    DeadlineWaiter DEFAULT = (deadlineNs, clock) -> {
        long now;
        while ((now = clock.nanoTime()) < deadlineNs) {
            long remaining = deadlineNs - now;
            if (remaining > 100_000) { // 100us
                LockSupport.parkNanos(remaining - 50_000);
            } else {
                Thread.onSpinWait();
            }
        }
    };

    void await(long deadlineNs, MonotonicClock clock);
}
