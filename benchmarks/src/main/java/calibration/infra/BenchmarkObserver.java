package calibration.infra;

import calibration.config.CalibrationBenchmarkConfig;
import io.euhedral_execution.core.control_plane.FragmentObserver;
import java.util.concurrent.atomic.AtomicBoolean;

public class BenchmarkObserver extends FragmentObserver {

    private final CalibrationBenchmarkConfig config;
    private final AtomicBoolean observing = new AtomicBoolean(false);

    public BenchmarkObserver(CalibrationBenchmarkConfig config) {
        this.config = config;
    }

    public void startObserving() {
        this.observing.setRelease(true);
    }

    public void stopObserving() {
        this.observing.setRelease(false);
    }

    @Override
    protected void cycleStartState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long completed,
            long batchSize,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double throughput) {
        if (!this.config.observeCycleStart() || !this.observing.getAcquire() || !Double.isFinite(throughput)) {
            return;
        }
    }

    @Override
    protected void batchProgressState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double avgServiceTime) {
        if (!this.config.observeBatchProgress() || !this.observing.getAcquire() || !Double.isFinite(avgServiceTime)) {
            return;
        }
    }

    @Override
    protected void batchCompleteState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput) {
        if (!this.config.observeBatchComplete()
                || !this.observing.getAcquire()
                || !Double.isFinite(avgServiceTime)
                || !Double.isFinite(throughput)) {
            return;
        }
    }

    @Override
    protected void rawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost) {
        if (!this.config.observeRawBodyCost() || !this.observing.getAcquire()) {
            return;
        }
    }

    @Override
    protected void idleBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            double smoothedBodyCost) {
        if (!this.config.observeIdleDecision() || !this.observing.getAcquire() || !Double.isFinite(smoothedBodyCost)) {
            return;
        }
    }

    @Override
    protected void execBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            double smoothedBodyCost) {
        if (!this.config.observeExecDecision() || !this.observing.getAcquire() || !Double.isFinite(smoothedBodyCost)) {
            return;
        }
    }
}
