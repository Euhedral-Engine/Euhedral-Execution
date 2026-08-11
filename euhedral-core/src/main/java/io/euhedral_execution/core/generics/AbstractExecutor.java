package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The terminal execution sink of Euhedral Core
///
/// `AbstractExecutor` is the final execution boundary for frames. It receives, executes, and
/// forwards completed frames to the completion channel.
///
/// Execution is intentionally minimal and without side effects. Completed frames are always pushed
/// into the completion sink, which decouples execution from downstream acknowledgment.
public abstract class AbstractExecutor implements CloneableObject {

    /// Fixed production cadence validated by the fragment body-cost sensor experiment.
    public static final int PRODUCTION_BODY_TIMING_INTERVAL = 256;

    private static final long NO_BODY_TIMING_SAMPLE = Long.MIN_VALUE;
    private static final LongSupplier SYSTEM_NANO_TIME = System::nanoTime;

    protected final int cpu;
    private final int bodyTimingInterval;
    private final LongSupplier bodyTimingClock;
    private final LongConsumer bodyTimingRecorder;
    private LongConsumer productionBodyTimingRecorder;
    private boolean inputConnected;
    private final Logger logger =
            LoggerFactory.getLogger(Constants.getLoggerName(this.getClass().getSimpleName()));

    /// Creates a production executor with diagnostic body timing disabled.
    protected AbstractExecutor(int cpu) {
        this.cpu = cpu;
        this.bodyTimingInterval = 0;
        this.bodyTimingClock = null;
        this.bodyTimingRecorder = null;
    }

    /// Creates an executor whose owner-local terminal samples only the virtual executor call.
    ///
    /// The clock and recorder are invoked once per `bodyTimingInterval` live frames. The caller
    /// owns publication of the recorded nanoseconds; this class adds no shared coordination.
    protected AbstractExecutor(
            int cpu, int bodyTimingInterval, LongSupplier bodyTimingClock, LongConsumer bodyTimingRecorder) {
        if (bodyTimingInterval <= 0) {
            throw new IllegalArgumentException("Body timing interval must be positive");
        }
        this.cpu = cpu;
        this.bodyTimingInterval = bodyTimingInterval;
        this.bodyTimingClock = Objects.requireNonNull(bodyTimingClock, "Body timing clock is required");
        this.bodyTimingRecorder = Objects.requireNonNull(bodyTimingRecorder, "Body timing recorder is required");
    }

    @Override
    public void input(LatticeSource stream) {
        this.inputConnected = true;
        LongConsumer productionRecorder = this.productionBodyTimingRecorder;
        int timingInterval = productionRecorder == null ? this.bodyTimingInterval : PRODUCTION_BODY_TIMING_INTERVAL;
        LongSupplier timingClock = timingInterval == 0 ? null : this.bodyTimingClock;
        if (timingClock == null && productionRecorder != null) {
            timingClock = SYSTEM_NANO_TIME;
        }
        stream.addDownstream(
                new ExecutionTerminal(timingInterval, timingClock, this.bodyTimingRecorder, productionRecorder));
    }

    /// Attaches the fragment-owned production body recorder before the executor receives input.
    ///
    /// The cloned fragment and executor share one synchronous owner thread after setup. The fixed
    /// cadence keeps the production signal compatible with the validated diagnostic sensor.
    public final void attachProductionBodyTimingRecorder(LongConsumer recorder) {
        Objects.requireNonNull(recorder, "Production body timing recorder is required");
        if (this.inputConnected) {
            throw new IllegalStateException("Production body timing must be attached before executor input");
        }
        if (this.productionBodyTimingRecorder != null) {
            throw new IllegalStateException("A production body timing recorder is already attached");
        }
        if (this.bodyTimingInterval != 0 && this.bodyTimingInterval != PRODUCTION_BODY_TIMING_INTERVAL) {
            throw new IllegalStateException("Diagnostic body timing cadence is incompatible with production timing");
        }
        this.productionBodyTimingRecorder = recorder;
    }

    public abstract void execute(AbstractFrame frame);

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }

    @Override
    public final AbstractExecutor clone(CloneConfig cloneConfig) {
        return hookOnClone(cloneConfig.effectiveCpus().nextSetBit(0));
    }

    public abstract AbstractExecutor hookOnClone(int cpu);

    private class ExecutionTerminal implements LatticeReceiver {

        private final int bodyTimingInterval;
        private final LongSupplier bodyTimingClock;
        private final LongConsumer diagnosticBodyTimingRecorder;
        private final LongConsumer productionBodyTimingRecorder;
        private int callsUntilBodyTimingSample;

        /// Snapshots all setup-only timing state before the terminal becomes reachable by a worker.
        private ExecutionTerminal(
                int bodyTimingInterval,
                LongSupplier bodyTimingClock,
                LongConsumer diagnosticBodyTimingRecorder,
                LongConsumer productionBodyTimingRecorder) {
            this.bodyTimingInterval = bodyTimingInterval;
            this.bodyTimingClock = bodyTimingClock;
            this.diagnosticBodyTimingRecorder = diagnosticBodyTimingRecorder;
            this.productionBodyTimingRecorder = productionBodyTimingRecorder;
            this.callsUntilBodyTimingSample = bodyTimingInterval;
        }

        @Override
        public void addUpstream(LatticeSource stream) {
            stream.request(Long.MAX_VALUE);
        }

        @Override
        public void push(AbstractFrame frame) {
            try {
                execute(frame);
            } catch (Exception e) {
                logger.error("Uncaught exception while running doFinally() on frame. {}", frame, e);
            }
        }

        private void execute(AbstractFrame frame) {
            long bodyTimingSample = NO_BODY_TIMING_SAMPLE;
            try {
                if (!frame.isAlive()) {
                    frame.throwCancelSignal();
                }
                bodyTimingSample = executeBody(frame);
            } catch (Exception e) {
                if (!(e instanceof AbstractFrame.CancelSignal)) {
                    logger.error("Uncaught exception while executing frame. {}", frame, e);
                    frame.doFinallyWithError(e);
                    return;
                }
            }

            if (bodyTimingSample != NO_BODY_TIMING_SAMPLE) {
                publishBodyTiming(bodyTimingSample);
            }

            try {
                frame.doFinally();
            } catch (Exception e) {
                logger.error("Uncaught exception while running doFinally. {}", frame);
            }
        }

        /// Executes one frame body and samples only the configured sparse diagnostic calls.
        private long executeBody(AbstractFrame frame) {
            LongSupplier clock = this.bodyTimingClock;
            if (clock == null || --this.callsUntilBodyTimingSample > 0) {
                AbstractExecutor.this.execute(frame);
                return NO_BODY_TIMING_SAMPLE;
            }

            this.callsUntilBodyTimingSample = this.bodyTimingInterval;
            long start = clock.getAsLong();
            AbstractExecutor.this.execute(frame);
            return clock.getAsLong() - start;
        }

        /// Publishes one successful sample without treating recorder failures as body failures.
        private void publishBodyTiming(long elapsedNanos) {
            if (this.diagnosticBodyTimingRecorder != null) {
                try {
                    this.diagnosticBodyTimingRecorder.accept(elapsedNanos);
                } catch (Exception e) {
                    logger.error("Uncaught exception while recording diagnostic executor body timing.", e);
                }
            }
            if (this.productionBodyTimingRecorder != null) {
                try {
                    this.productionBodyTimingRecorder.accept(elapsedNanos);
                } catch (Exception e) {
                    logger.error("Uncaught exception while recording production executor body timing.", e);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // ControlPlaneFragment should never signal an error
        }

        @Override
        public void onComplete() {
            // ControlPlaneFragment should never signal complete
        }
    }
}
