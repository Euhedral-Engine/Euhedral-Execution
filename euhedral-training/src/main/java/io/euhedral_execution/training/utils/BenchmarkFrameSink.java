package io.euhedral_execution.training.utils;

import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.AbstractIngestSink;

/**
 * A repeatable benchmark source with an explicit pause barrier and permanent hard stop.
 */
public class BenchmarkFrameSink extends AbstractIngestSink {

    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(1);

    private final Delegate delegate;

    public BenchmarkFrameSink(AbstractFrame[] frames) {
        Objects.requireNonNull(frames);
        if (frames.length == 0) {
            throw new IllegalArgumentException("BenchmarkFrameSink requires at least one frame");
        }
        this.delegate = new Delegate(frames);
        VarHandle.fullFence();
    }

    @Override
    public LatticeSource getDelegate() {
        return this.delegate;
    }

    /**
     * Enables this source after a trial reset has completed.
     */
    public void resume() {
        this.delegate.resume();
    }

    /**
     * Stops new emissions and waits for any in-flight pull/request callback to leave.
     */
    public void pause(Duration timeout) {
        this.delegate.pause(timeout);
    }

    /**
     * Permanently prevents workers from pulling any additional frames.
     */
    public void hardStop(Duration timeout) {
        this.delegate.hardStop(timeout);
    }

    @Override
    public void complete() {
        hardStop(DEFAULT_STOP_TIMEOUT);
    }

    @Override
    public boolean isComplete() {
        return this.delegate.isComplete();
    }

    public void resetCounter() {
        this.delegate.consumed = 0;
        VarHandle.releaseFence();
    }

    public long getConsumed() {
        VarHandle.acquireFence();
        return this.delegate.consumed;
    }

    protected static final class Delegate extends AbstractIngestSink.Delegate {

        private final AtomicBoolean enabled = new AtomicBoolean(false);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AbstractFrame[] array;

        private int start;
        private long consumed;

        Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public long hookOnPull(Consumer<AbstractFrame> consumer,
                Function<AbstractFrame, Boolean> stopCondition, long requested) {
            if (!enter()) {
                return 0;
            }

            long total = 0;
            try {
                while (total < requested && this.enabled.getOpaque() && !isComplete()) {
                    AbstractFrame frame = this.array[this.start];
                    if (stopCondition.apply(frame)) {
                        break;
                    }
                    this.start = (this.start + 1) % this.array.length;
                    consumer.accept(Objects.requireNonNull(frame));
                    total++;
                }
                return total;
            } finally {
                record(total);
                this.inFlight.decrementAndGet();
            }
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long requested) {
            if (!enter()) {
                this.demand.setRelease(0);
                return;
            }

            long total = 0;
            try {
                while (total < requested && this.enabled.getOpaque() && !isComplete()) {
                    AbstractFrame frame = this.array[this.start];
                    this.start = (this.start + 1) % this.array.length;
                    terminal.push(Objects.requireNonNull(frame));
                    total++;
                }
            } finally {
                if (this.enabled.getOpaque() && !isComplete()) {
                    addAndGetDemand(-total);
                } else {
                    this.demand.setRelease(0);
                }
                record(total);
                this.inFlight.decrementAndGet();
            }
        }

        private boolean enter() {
            if (!this.enabled.getOpaque() || isComplete()) {
                return false;
            }
            this.inFlight.incrementAndGet();
            if (!this.enabled.getAcquire() || isComplete()) {
                this.inFlight.decrementAndGet();
                return false;
            }
            return true;
        }

        private void record(long total) {
            this.consumed += total;
            VarHandle.releaseFence();
        }

        void resume() {
            if (isComplete()) {
                throw new IllegalStateException("Cannot resume a completed benchmark source");
            }
            this.demand.setRelease(0);
            this.enabled.setRelease(true);
        }

        void pause(Duration timeout) {
            Objects.requireNonNull(timeout);
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Pause timeout must be positive");
            }
            this.enabled.setRelease(false);
            this.demand.setRelease(0);
            awaitCallbacks(timeout);
        }

        void hardStop(Duration timeout) {
            this.enabled.setRelease(false);
            this.demand.setRelease(0);
            super.complete();
            awaitCallbacks(timeout);
        }

        private void awaitCallbacks(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (this.inFlight.getAcquire() != 0 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(5_000L);
            }
            if (this.inFlight.getAcquire() != 0) {
                throw new IllegalStateException("Timed out stopping benchmark source callbacks");
            }
        }
    }
}
