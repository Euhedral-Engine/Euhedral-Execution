package io.euhedral_execution.core.utils;

public class StopWatch {
    private final int recordingInterval;
    private final int mask;

    private boolean started = false;

    private long ticks = 0L;
    private long lastTime = 0;

    public StopWatch() {
        this.recordingInterval = 256;
        this.mask = 255;
    }

    public StopWatch(int recordingInterval) {
        if (recordingInterval < 0) {
            throw new IllegalArgumentException("recording interval must be a positive integer");
        }
        this.recordingInterval = recordingInterval;
        boolean pow2 = Integer.highestOneBit((recordingInterval - 1) << 1) == recordingInterval;
        this.mask = pow2 ? recordingInterval - 1 : -1;
    }

    /// Starts the clock every `recordingInterval` number of calls
    public void start() {
        if (this.started) {
            return;
        }
        boolean ready = this.mask > 0 ? (ticks & mask) == 0 : ticks % recordingInterval == 0;
        if (!ready) {
            ticks++;
            return;
        }
        this.started = true;
        this.lastTime = System.nanoTime();
    }

    /// Returns 0 if the clock wasn't started.
    public long stop() {
        if (started) {
            long elapsed = System.nanoTime() - lastTime;
            ticks++;
            started = false;
            return elapsed;
        }
        return 0;
    }
}
