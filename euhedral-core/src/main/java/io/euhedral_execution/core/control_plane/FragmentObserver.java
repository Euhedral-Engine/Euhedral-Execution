package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.flow_control.PullBucketDivisionMode;

public abstract class FragmentObserver {

    /// Returns the production pull-bucketing baseline unless an experiment explicitly overrides it.
    public long pullBucketTarget() {
        return 2_048L;
    }

    /// Returns the production pull-bucketing baseline unless an experiment explicitly overrides it.
    public PullBucketDivisionMode pullBucketDivisionMode() {
        return PullBucketDivisionMode.FLOOR;
    }

    /// Returns whether benchmark-only source-lock convoy diagnostics should be collected.
    public boolean observesPullConvoy() {
        return false;
    }

    /// Returns whether benchmark-only contention-staleness diagnostics should be collected.
    public boolean observesContentionStaleness() {
        return false;
    }

    /// This method will be called concurrently by fragments.
    ///
    /// Doubles are not sanitized before publishing.
    protected abstract void cycleStartState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long completed,
            long batchSize,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double throughput);

    /// This method will be called concurrently by fragments.
    ///
    /// Doubles are not sanitized before publishing.
    protected abstract void batchProgressState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double avgServiceTime);

    /// This method will be called concurrently by fragments.
    ///
    /// Doubles are not sanitized before publishing.
    protected abstract void batchCompleteState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput);

    /// This method will be called concurrently by fragments.
    ///
    /// The instant raw body cost reported by fragments
    protected abstract void rawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost);

    /// This method will be called concurrently by fragments.
    ///
    /// The idle branch of the decision tree chosen. `contententionPolicy` and `bodyPolicy` are the indexes of their
    /// corresponding idle policy lists. Doubles are not sanitized before publishing
    protected abstract void idleBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            long contention,
            double smoothedBodyCost);

    /// This method will be called concurrently by fragments.
    ///
    /// The execution branch of the decision tree chosen. `contententionPolicy` and `bodyPolicy` are the indexes of
    /// their
    /// corresponding execution policy lists. Doubles are not sanitized before publishing
    protected abstract void execBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            long contention,
            double smoothedBodyCost);

    /// This method will be called concurrently by fragments when contention-staleness diagnostics are enabled.
    ///
    /// The acquisition counters are cumulative within the current fragment reset interval. A negative idle duration
    /// means that no idle branch was selected during this cycle.
    protected void contentionStalenessState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long measuredContention,
            long lastRawContention,
            long contentionObservationCount,
            long lastContentionObservationNs,
            long cyclesSinceContentionObservation,
            long nanosSinceContentionObservation,
            long consecutiveIdleDecisions,
            long idleDurationSelectedNs,
            long successfulAcquisitionCount,
            long failedAcquisitionCount,
            long totalAcquisitionAttempts,
            int executionPath,
            long localCacheCount,
            long productiveHandleCount,
            int registeredWorkers,
            int workerRank) {}

    /// Records one bounded source-handle acquisition observation for calibration runs.
    public void pullConvoyState(
            long eventNs,
            long handleId,
            int attemptingCore,
            int ownerCore,
            long requestedDemand,
            long calculatedPullSize,
            long producedFrameCount,
            boolean acquired,
            long lockHoldDurationNs) {}
}
